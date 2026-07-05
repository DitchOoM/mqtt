package com.ditchoom.mqtt.client.net

import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.toReadBuffer
import com.ditchoom.mqtt.controlpacket.IConnectionRequest
import com.ditchoom.mqtt.controlpacket.OpaquePublishPayloadCodec
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.TopicFilter
import com.ditchoom.mqtt.controlpacket.TopicName
import com.ditchoom.mqtt.controlpacket.WillConfig
import com.ditchoom.mqtt.controlpacket.format.ReasonCode
import com.ditchoom.mqtt5.controlpacket.ConnectProperties
import com.ditchoom.mqtt5.controlpacket.ConnectWillProperties
import com.ditchoom.mqtt5.controlpacket.ConnectionRequest
import com.ditchoom.mqtt5.controlpacket.ControlPacketV5
import com.ditchoom.mqtt5.controlpacket.PublishProperties
import com.ditchoom.socket.TransportKind
import com.ditchoom.socket.networkCapabilities
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * MQTT 5.0 behavioral scenarios against the eclipse-paho conformance broker
 * (localhost:1883). Gated behind -PconformanceTests — see [PahoConformance].
 */
class PahoConformanceV5Test {
    private fun v5Request(
        clientId: String,
        cleanStart: Boolean = true,
        keepAliveSeconds: Int = 15,
        props: ConnectProperties = ConnectProperties(),
    ): IConnectionRequest =
        ConnectionRequest(
            clientId = clientId,
            keepAliveSeconds = keepAliveSeconds,
            cleanStart = cleanStart,
            props = props,
        )

    @Test
    fun cleanStartConnackHasNoSessionPresent() =
        runTestNoTimeSkipping {
            if (TransportKind.TCP !in networkCapabilities().transports) return@runTestNoTimeSkipping
            val (client, connack) = startPahoClient(this, v5Request(PahoConformance.clientId("v5clean")))
            assertTrue(connack.isSuccessful, "CONNACK should be successful")
            assertFalse(connack.sessionPresent, "[MQTT-3.2.2-2] cleanStart=1 must yield sessionPresent=0")
            client.shutdown()
        }

    @Test
    fun publishSubscribeEchoAllQos() =
        runTestNoTimeSkipping(timeout = 60.seconds) {
            if (TransportKind.TCP !in networkCapabilities().transports) return@runTestNoTimeSkipping
            val (client, _) = startPahoClient(this, v5Request(PahoConformance.clientId("v5echo")))
            val topic = TopicName.fromOrThrow(PahoConformance.topic("v5echo"))
            val flow = client.observe(TopicFilter.fromOrThrow(topic.toString()), OpaquePublishPayloadCodec)
            val received = mutableSetOf<String>()
            val collector =
                async {
                    kotlinx.coroutines.withTimeout(30.seconds) {
                        flow.take(3).collect { (pub, _) ->
                            received += "${pub.opaquePayloadUtf8()}@${pub.qualityOfService.integerValue.toInt()}"
                        }
                    }
                }
            sendAllMessageTypes(client, topic, "conformance")
            collector.await()
            assertEquals(
                setOf("conformance0@0", "conformance1@1", "conformance2@2"),
                received,
                "each QoS level should echo exactly once",
            )
            client.shutdown(drain = true)
        }

    @Test
    fun sessionExpiryKeepsSessionAcrossReconnect() =
        runTestNoTimeSkipping(timeout = 60.seconds) {
            if (TransportKind.TCP !in networkCapabilities().transports) return@runTestNoTimeSkipping
            val clientId = PahoConformance.clientId("v5expiry")
            val props = ConnectProperties(sessionExpiryIntervalSeconds = 300uL)
            // First session: cleanStart=1 + a subscription creates broker-side session state.
            val (first, _) = startPahoClient(this, v5Request(clientId, cleanStart = true, props = props))
            first
                .subscribe(PahoConformance.topic("v5expiry"), OpaquePublishPayloadCodec, maxQos = QualityOfService.AT_LEAST_ONCE)
                .subAck
                .await()
            first.shutdown()
            // Second session with the same clientId and cleanStart=0: the broker must
            // still hold the session (§3.1.2.11.2 session expiry interval).
            val (second, connack) = startPahoClient(this, v5Request(clientId, cleanStart = false, props = props))
            assertTrue(connack.sessionPresent, "session with 300s expiry must survive a graceful reconnect")
            second.shutdown()
        }

    @Test
    fun userPropertiesRoundTrip() =
        runTestNoTimeSkipping {
            if (TransportKind.TCP !in networkCapabilities().transports) return@runTestNoTimeSkipping
            val (client, _) = startPahoClient(this, v5Request(PahoConformance.clientId("v5userprops")))
            val topic = PahoConformance.topic("v5userprops")
            val flow = client.observe(TopicFilter.fromOrThrow(topic), OpaquePublishPayloadCodec)
            val receiving = async { expectMessage(flow) }
            client.subscribe(topic, OpaquePublishPayloadCodec, maxQos = QualityOfService.AT_LEAST_ONCE).subAck.await()
            val pub =
                ControlPacketV5.Publish.ofRaw(
                    topic = TopicName.fromOrThrow(topic),
                    qos = QualityOfService.AT_LEAST_ONCE,
                    payload = "props".toReadBuffer(Charset.UTF8),
                    properties = PublishProperties(userProperty = listOf("origin" to "conformance")),
                )
            awaitPublishComplete(client.publish(pub))
            val message = receiving.await() as ControlPacketV5.Publish<*>
            assertEquals(
                listOf("origin" to "conformance"),
                message.typedProperties.userProperty,
                "[MQTT-3.3.2-17] the broker must forward PUBLISH user properties unaltered",
            )
            client.shutdown()
        }

    @Test
    fun subscribeAndUnsubscribeReasonCodes() =
        runTestNoTimeSkipping {
            if (TransportKind.TCP !in networkCapabilities().transports) return@runTestNoTimeSkipping
            val (client, _) = startPahoClient(this, v5Request(PahoConformance.clientId("v5reason")))
            val topic = PahoConformance.topic("v5reason")
            val subAck =
                client
                    .subscribe(topic, OpaquePublishPayloadCodec, maxQos = QualityOfService.EXACTLY_ONCE)
                    .subAck
                    .await() as ControlPacketV5.SubAck
            assertEquals(listOf(ReasonCode.GRANTED_QOS_2), subAck.payload, "SUBACK must grant the requested QoS")
            val unsubAck =
                client.unsubscribe(topic).unsubAck.await() as ControlPacketV5.UnsubAck
            assertEquals(listOf(ReasonCode.SUCCESS), unsubAck.reasonCodes, "UNSUBACK must report success")
            client.shutdown()
        }

    @Test
    fun concurrentQos0PublishesAllDelivered() =
        runTestNoTimeSkipping(timeout = 45.seconds) {
            if (TransportKind.TCP !in networkCapabilities().transports) return@runTestNoTimeSkipping
            val (client, _) = startPahoClient(this, v5Request(PahoConformance.clientId("v5q0burst")))
            val topic = PahoConformance.topic("v5q0burst")
            val flow = client.observe(TopicFilter.fromOrThrow(topic), OpaquePublishPayloadCodec)
            val receiving = async { kotlinx.coroutines.withTimeout(20.seconds) { flow.take(10).toList() } }
            client.subscribe(topic, OpaquePublishPayloadCodec, maxQos = QualityOfService.AT_MOST_ONCE).subAck.await()
            (1..10)
                .map { i ->
                    async { client.publish(topic, QualityOfService.AT_MOST_ONCE, "m$i".toReadBuffer(Charset.UTF8)) }
                }.awaitAll()
            assertEquals(10, receiving.await().size, "all concurrent QoS 0 publishes must be delivered")
            assertEquals(1L, client.connectionCount(), "connection must survive concurrent QoS 0 publishes")
            client.shutdown()
        }

    /**
     * Regression for DitchOoM/mqtt#12. Two concurrent QoS 1 publishes used to
     * deterministically corrupt the connection: the unguarded packet-ID allocator in
     * [com.ditchoom.mqtt.InMemoryPersistence] (`getPacketId`), running on the
     * multi-threaded default dispatcher, could hand both publishes the SAME packet
     * identifier — confirmed on the wire against this broker — so only one of the two
     * PUBACKs could be matched and the other publish's state flow never completed
     * (`awaitAll` hangs). A torn read around the zero-wrap guard could also emit a zero
     * packet id, which the broker rejects with "[MQTT-4.8.0-1] 'transient error' reading
     * packet, closing connection".
     *
     * Fixed by serializing `getPacketId` + the dependent persistence map writes behind a
     * Mutex, and by guarding the processor's QoS 1/2 state maps. The same burst at QoS 0
     * ([concurrentQos0PublishesAllDelivered]) never hit this because QoS 0 allocates no
     * packet id.
     *
     * Kept at 2 concurrent publishes so it stays within the broker's advertised Receive
     * Maximum of 2 — enforcing that send quota client-side ([MQTT-3.3.4-9]) is a separate
     * item.
     */
    @Test
    fun concurrentQos1PublishesAllComplete() =
        runTestNoTimeSkipping(timeout = 60.seconds) {
            if (TransportKind.TCP !in networkCapabilities().transports) return@runTestNoTimeSkipping
            val (client, _) = startPahoClient(this, v5Request(PahoConformance.clientId("v5burst")))
            val topic = PahoConformance.topic("v5burst")
            val publishes =
                (1..2).map { i ->
                    async {
                        awaitPublishComplete(
                            client.publish(topic, QualityOfService.AT_LEAST_ONCE, "m$i".toReadBuffer(Charset.UTF8)),
                        )
                    }
                }
            publishes.awaitAll()
            assertEquals(1L, client.connectionCount(), "connection must survive concurrent QoS 1 publishes")
            client.shutdown()
        }

    /**
     * [MQTT-3.3.4-8] / [MQTT-3.3.4-9] send-quota flow control. The paho broker advertises
     * `Receive Maximum: 2` in its CONNACK, so a client that fires more than two concurrent
     * QoS 1 publishes MUST hold the surplus back rather than putting >2 on the wire — the
     * broker disconnects a client that exceeds its Receive Maximum. Before the client-side
     * semaphore this burst of six killed the connection; now all six complete over a single
     * connection as slots free up on each PUBACK.
     */
    @Test
    fun receiveMaximumThrottlesConcurrentPublishes() =
        runTestNoTimeSkipping(timeout = 60.seconds) {
            if (TransportKind.TCP !in networkCapabilities().transports) return@runTestNoTimeSkipping
            val (client, connack) = startPahoClient(this, v5Request(PahoConformance.clientId("v5recvmax")))
            assertEquals(2, connack.receiveMaximum, "paho broker advertises Receive Maximum = 2")
            val topic = PahoConformance.topic("v5recvmax")
            val publishes =
                (1..6).map { i ->
                    async {
                        awaitPublishComplete(
                            client.publish(topic, QualityOfService.AT_LEAST_ONCE, "m$i".toReadBuffer(Charset.UTF8)),
                        )
                    }
                }
            publishes.awaitAll()
            assertEquals(
                1L,
                client.connectionCount(),
                "connection must survive a burst that exceeds the broker's Receive Maximum",
            )
            client.shutdown()
        }

    @Test
    fun willMessageDeliveredOnUngracefulDisconnect() =
        runTestNoTimeSkipping(timeout = 60.seconds) {
            if (TransportKind.TCP !in networkCapabilities().transports) return@runTestNoTimeSkipping
            val willTopic = PahoConformance.topic("v5will")
            val willRequest =
                ConnectionRequest(
                    clientId = PahoConformance.clientId("v5willpub"),
                    keepAliveSeconds = 15,
                    cleanStart = true,
                    will =
                        WillConfig.Enabled(
                            topic = TopicName.fromOrThrow(willTopic),
                            payload = "gone".toReadBuffer(Charset.UTF8),
                            qos = QualityOfService.AT_LEAST_ONCE,
                            retain = false,
                        ),
                    willProperties = ConnectWillProperties(),
                )
            val (dying, _) = startPahoClient(this, willRequest)
            val (watcher, _) = startPahoClient(this, v5Request(PahoConformance.clientId("v5willsub")))
            val flow = watcher.observe(TopicFilter.fromOrThrow(willTopic), OpaquePublishPayloadCodec)
            val receiving = async { expectMessage(flow, timeout = 20.seconds) }
            watcher.subscribe(willTopic, OpaquePublishPayloadCodec, maxQos = QualityOfService.AT_LEAST_ONCE).subAck.await()
            dying.shutdown(sendDisconnect = false)
            val will = receiving.await()
            assertEquals("gone", will.opaquePayloadUtf8())
            watcher.shutdown()
        }
}
