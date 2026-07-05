package com.ditchoom.mqtt.client

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.Payload
import com.ditchoom.buffer.codec.ownedBytesFrom
import com.ditchoom.buffer.flow.Connection
import com.ditchoom.mqtt.InMemoryPersistence
import com.ditchoom.mqtt.client.net.runTestNoTimeSkipping
import com.ditchoom.mqtt.connection.MqttConnectionOptions
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.ISubscribeRequest
import com.ditchoom.mqtt.controlpacket.MqttFixedHeader
import com.ditchoom.mqtt.controlpacket.OpaquePublishPayload
import com.ditchoom.mqtt.controlpacket.OpaquePublishPayloadCodec
import com.ditchoom.mqtt.controlpacket.PublishMessage
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt3.controlpacket.ConnectionAcknowledgment
import com.ditchoom.mqtt3.controlpacket.ConnectionRequest
import com.ditchoom.mqtt3.controlpacket.PublishMessageV4
import com.ditchoom.mqtt3.controlpacket.SubAckReturnCode
import com.ditchoom.mqtt3.controlpacket.SubscribeAcknowledgement
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.seconds

/**
 * Regression coverage for the `MqttClient.start` ↔ `MqttCodec` wiring on incoming PUBLISH.
 *
 * Pre-fix behaviour: `MqttClient.start` threaded the per-client `TopicCodecRegistry` into
 * `defaultSingleConnection` only when the caller supplied no `connectSingle`. A
 * caller-supplied `connectSingle` (e.g. every `createConnectFactory(...)` test path)
 * silently used `publishCodecForTopic = { null }`, so `MqttCodec.decode` threw
 * `MissingCodecException` for every incoming PUBLISH — broker connection died, SUBACK
 * still arrived on the reconnect, but no PUBLISH ever made it to the dispatcher and the
 * handler-based `subscribe<P>(filter, codec, qos, handler)` never fired.
 *
 * Post-fix: `connectSingle`'s signature carries the per-topic codec lookup as a parameter;
 * `ConnectivityManager` invokes `connectSingle(op, publishCodecForTopic)` so the registry
 * cannot be bypassed. These tests pin the structural contract — they fail at compile-time
 * if the signature regresses, and at run-time if the lookup ever returns null for a topic
 * the client just subscribed to.
 */
class MqttClientCodecRoutingRegressionTest {
    @Test
    fun connectSingleReceivesLookupThatResolvesSubscribedCodec() =
        runTestNoTimeSkipping {
            val capturedLookup = CompletableDeferred<(String) -> Codec<out Payload>?>()
            val fakeConn = FakeConnection()
            val persistence = InMemoryPersistence()
            val broker =
                persistence.addBroker(
                    listOf(MqttConnectionOptions.SocketConnection("test", 1883)),
                    sampleConnectionRequest(),
                )
            // Seed CONNACK so MqttClient.start can complete the handshake.
            fakeConn.inbound.send(ConnectionAcknowledgment())

            val scope = CoroutineScope(coroutineContext + Dispatchers.Default)
            val client =
                MqttClient.start(
                    scope = scope,
                    broker = broker,
                    persistence = persistence,
                    connectSingle = { _, lookup ->
                        capturedLookup.complete(lookup)
                        fakeConn
                    },
                )
            try {
                val lookup = withTimeout(5.seconds) { capturedLookup.await() }
                assertEquals(
                    null,
                    lookup("regression/topic"),
                    "before subscribe, lookup should not resolve a codec",
                )

                val op =
                    client.subscribe(
                        topicFilter = "regression/topic",
                        payloadCodec = OpaquePublishPayloadCodec,
                        maxQos = QualityOfService.AT_MOST_ONCE,
                    ) { _, _ -> }

                // ConnectivityManager.connectAndHandshake sends CONNECT before writeLoop starts;
                // drain it so the next outbound packet is the SUBSCRIBE we care about.
                val sub = withTimeout(5.seconds) { fakeConn.nextOutboundOfType<ISubscribeRequest>() }
                fakeConn.inbound.send(
                    SubscribeAcknowledgement(
                        sub.packetIdentifier.toUShort(),
                        listOf(SubAckReturnCode.SuccessMaximumQoS0),
                    ),
                )
                withTimeout(5.seconds) { op.subAck.await() }

                assertSame(
                    OpaquePublishPayloadCodec,
                    lookup("regression/topic"),
                    "post-subscribe, lookup must resolve to the registered codec",
                )
            } finally {
                client.shutdown(sendDisconnect = false)
            }
        }

    @Test
    fun handlerSubscribeFiresOnIncomingPublishDispatch() =
        runTestNoTimeSkipping {
            val fakeConn = FakeConnection()
            val persistence = InMemoryPersistence()
            val broker =
                persistence.addBroker(
                    listOf(MqttConnectionOptions.SocketConnection("test", 1883)),
                    sampleConnectionRequest(),
                )
            fakeConn.inbound.send(ConnectionAcknowledgment())

            val scope = CoroutineScope(coroutineContext + Dispatchers.Default)
            val client =
                MqttClient.start(
                    scope = scope,
                    broker = broker,
                    persistence = persistence,
                    connectSingle = { _, _ -> fakeConn },
                )
            try {
                val received = CompletableDeferred<Pair<String, OpaquePublishPayload>>()

                val op =
                    client.subscribe(
                        topicFilter = "regression/topic",
                        payloadCodec = OpaquePublishPayloadCodec,
                        maxQos = QualityOfService.AT_MOST_ONCE,
                    ) { pub: PublishMessage, payload: OpaquePublishPayload ->
                        received.complete(pub.topic.toString() to payload)
                    }

                val sub = withTimeout(5.seconds) { fakeConn.nextOutboundOfType<ISubscribeRequest>() }
                fakeConn.inbound.send(
                    SubscribeAcknowledgement(
                        sub.packetIdentifier.toUShort(),
                        listOf(SubAckReturnCode.SuccessMaximumQoS0),
                    ),
                )
                withTimeout(5.seconds) { op.subAck.await() }

                // Construct a typed PUBLISH and feed it through the receive channel — verifies
                // the registered handler dispatches once the message reaches the processor's
                // PublishDispatcher.
                val payloadBytes = byteArrayOf(0x68, 0x69) // "hi"
                val payloadBuffer =
                    BufferFactory.Default.allocate(payloadBytes.size).apply {
                        writeBytes(payloadBytes)
                        resetForRead()
                    }
                val opaque = OpaquePublishPayload(ownedBytesFrom(payloadBuffer))
                fakeConn.inbound.send(
                    PublishMessageV4(
                        header = MqttFixedHeader(0x30u), // PUBLISH, QoS 0
                        topicName = "regression/topic",
                        packetId = null,
                        payload = opaque,
                    ),
                )

                val (topic, payloadOut) = withTimeout(5.seconds) { received.await() }
                assertEquals("regression/topic", topic)
                assertEquals(opaque, payloadOut)
            } finally {
                client.shutdown(sendDisconnect = false)
            }
        }
}

private fun sampleConnectionRequest(): ConnectionRequest =
    ConnectionRequest(
        variableHeader = ConnectionRequest.VariableHeader(cleanSession = true, keepAliveSeconds = 60),
        payload = ConnectionRequest.Payload(clientId = "codec-routing-regression"),
    )

/**
 * In-memory `Connection<ControlPacket>` backed by two channels — outbound packets the
 * `ConnectivityManager` writes; inbound packets the test feeds back. Each call to
 * `receive()` returns a fresh cold flow over the shared inbound channel so the manager
 * can consume the handshake CONNACK with `first()` and the rest of the session via
 * `collect`.
 */
private class FakeConnection : Connection<ControlPacket> {
    override val id: Long = 0L
    val outbound: Channel<ControlPacket> = Channel(Channel.UNLIMITED)
    val inbound: Channel<ControlPacket> = Channel(Channel.UNLIMITED)

    override suspend fun send(message: ControlPacket) {
        outbound.send(message)
    }

    override fun receive(): Flow<ControlPacket> =
        flow {
            for (item in inbound) emit(item)
        }

    override suspend fun close() {
        outbound.close()
        inbound.close()
    }

    /**
     * Drain packets from [outbound] until one matches [T]. ConnectivityManager sends the
     * CONNECT directly through `conn.send` during handshake — before the write-loop ever
     * starts — so callers that want to inspect a post-handshake packet need to skip past
     * any preceding setup packets.
     */
    suspend inline fun <reified T : ControlPacket> nextOutboundOfType(): T {
        while (true) {
            val packet = outbound.receive()
            if (packet is T) return packet
        }
    }
}
