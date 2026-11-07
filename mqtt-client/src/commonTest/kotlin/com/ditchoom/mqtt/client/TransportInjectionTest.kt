package com.ditchoom.mqtt.client

import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.mqtt.InMemoryPersistence
import com.ditchoom.mqtt.client.net.runTestNoTimeSkipping
import com.ditchoom.mqtt.controlpacket.IConnectionRequest
import com.ditchoom.mqtt.controlpacket.OpaquePublishPayloadCodec
import com.ditchoom.mqtt.controlpacket.PublishMessage
import com.ditchoom.mqtt3.controlpacket.ConnectionAcknowledgment
import com.ditchoom.mqtt3.controlpacket.ConnectionRequest
import com.ditchoom.mqtt3.controlpacket.ControlPacketV4Factory
import com.ditchoom.socket.TlsConfig
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.transport.CodecConnection
import com.ditchoom.socket.transport.MemoryTransport
import com.ditchoom.socket.transport.OverflowPolicy
import com.ditchoom.socket.transport.Transport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * The client speaks MQTT over whatever socket [Transport] an [MqttEndpoint] carries. Here that is
 * a custom transport handing out one end of an in-memory byte pipe, with a broker that decodes
 * the raw bytes on the other end, so the whole production path (connector, codec framing, writer)
 * runs without a network.
 */
class TransportInjectionTest {
    @Test
    fun clientConnectsAndPublishesOverAnInjectedTransport() =
        runTestNoTimeSkipping {
            val (clientEnd, brokerEnd) = MemoryTransport.createPair()
            val transport = RecordingTransport(clientEnd)
            val scope = CoroutineScope(coroutineContext + Dispatchers.Default)
            val broker =
                CodecConnection(
                    brokerEnd,
                    MqttCodec(ControlPacketV4Factory) { TopicCodec.Routed(OpaquePublishPayloadCodec) },
                    scope,
                    outboundCapacity = 8,
                    overflowPolicy = OverflowPolicy.Suspend,
                )
            val received = CompletableDeferred<PublishMessage>()
            val brokerJob =
                scope.launch {
                    broker.receive().collect { packet ->
                        when (packet) {
                            is IConnectionRequest -> broker.send(ConnectionAcknowledgment())
                            is PublishMessage -> received.complete(packet)
                            else -> Unit
                        }
                    }
                }
            val persistence = InMemoryPersistence()
            val brokerRecord = persistence.addBroker(ConnectionRequest(clientId = "injected", keepAliveSeconds = 60))
            val endpoint =
                MqttEndpoint(
                    "in-memory",
                    1,
                    transport,
                    TransportConfig(readPolicy = ReadPolicy.Bounded(1.seconds), tls = TlsConfig.DEFAULT),
                )
            val client = MqttClient.start(scope, brokerRecord, persistence, endpoint)
            try {
                withTimeout(5.seconds) { client.awaitConnectivity() }
                client.publish("injected/topic")

                val publish = withTimeout(5.seconds) { received.await() }
                assertEquals("injected/topic", publish.topic.toString())

                val dialled = transport.dialled.await()
                assertEquals("in-memory" to 1, dialled.first)
                assertIs<ReadPolicy.UntilClosed>(dialled.second.readPolicy, "MQTT reads must never time out")
                assertEquals(TlsConfig.DEFAULT, dialled.second.tls, "TLS comes from the endpoint's config")
            } finally {
                client.shutdown(sendDisconnect = false)
                brokerJob.cancel()
                broker.close()
            }
        }
}

private class RecordingTransport(
    private val stream: ByteStream,
) : Transport {
    val dialled = CompletableDeferred<Pair<Pair<String, Int>, TransportConfig>>()

    override suspend fun connect(
        hostname: String,
        port: Int,
        config: TransportConfig,
    ): ByteStream {
        dialled.complete((hostname to port) to config)
        return stream
    }
}
