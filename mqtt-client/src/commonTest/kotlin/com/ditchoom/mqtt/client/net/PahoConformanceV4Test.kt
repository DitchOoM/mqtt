package com.ditchoom.mqtt.client.net

import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.toReadBuffer
import com.ditchoom.mqtt.controlpacket.IConnectionRequest
import com.ditchoom.mqtt.controlpacket.OpaquePublishPayloadCodec
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.TopicFilter
import com.ditchoom.mqtt.controlpacket.TopicName
import com.ditchoom.mqtt3.controlpacket.ConnectionRequest
import com.ditchoom.socket.TransportKind
import com.ditchoom.socket.networkCapabilities
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * MQTT 3.1.1 behavioral scenarios against the eclipse-paho conformance broker
 * (localhost:1883). Gated behind -PconformanceTests — see [PahoConformance].
 */
class PahoConformanceV4Test {
    private fun v4Request(
        clientId: String,
        cleanSession: Boolean = true,
        keepAliveSeconds: Int = 15,
    ): IConnectionRequest =
        ConnectionRequest(
            variableHeader =
                ConnectionRequest.VariableHeader(
                    cleanSession = cleanSession,
                    keepAliveSeconds = keepAliveSeconds,
                ),
            payload = ConnectionRequest.Payload(clientId = clientId),
        )

    @Test
    fun cleanSessionConnackHasNoSessionPresent() =
        runTestNoTimeSkipping {
            if (TransportKind.TCP !in networkCapabilities().transports) return@runTestNoTimeSkipping
            val (client, connack) = startPahoClient(this, v4Request(PahoConformance.clientId("v4clean")))
            assertTrue(connack.isSuccessful, "CONNACK should be successful")
            assertFalse(connack.sessionPresent, "[MQTT-3.2.2-1] cleanSession=1 must yield sessionPresent=0")
            client.shutdown()
        }

    @Test
    fun publishSubscribeEchoAllQos() =
        runTestNoTimeSkipping(timeout = 60.seconds) {
            if (TransportKind.TCP !in networkCapabilities().transports) return@runTestNoTimeSkipping
            val (client, _) = startPahoClient(this, v4Request(PahoConformance.clientId("v4echo")))
            val topic = TopicName.fromOrThrow(PahoConformance.topic("v4echo"))
            val flow = client.observe(TopicFilter.fromOrThrow(topic.toString()), OpaquePublishPayloadCodec)
            val received = mutableSetOf<String>()
            val collector =
                async {
                    withTimeout(30.seconds) {
                        flow.take(3).collect { (pub, _) ->
                            received += "${pub.opaquePayloadUtf8()}@${pub.qualityOfService.integerValue.toInt()}"
                        }
                    }
                }
            // Exercises SUBSCRIBE (max QoS 2), PUBLISH at QoS 0/1/2 incl. full QoS 2
            // handshake, and UNSUBSCRIBE — the core spec flows.
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
    fun qosDowngradedToSubscriptionMaximum() =
        runTestNoTimeSkipping {
            if (TransportKind.TCP !in networkCapabilities().transports) return@runTestNoTimeSkipping
            val (client, _) = startPahoClient(this, v4Request(PahoConformance.clientId("v4qosdown")))
            val topic = PahoConformance.topic("v4qosdown")
            val sub = client.subscribe(topic, OpaquePublishPayloadCodec, maxQos = QualityOfService.AT_LEAST_ONCE)
            sub.subAck.await()
            val flow = client.observe(TopicFilter.fromOrThrow(topic), OpaquePublishPayloadCodec)
            val receiving = async { expectMessage(flow) }
            awaitPublishComplete(
                client.publish(topic, QualityOfService.EXACTLY_ONCE, "x".toReadBuffer(Charset.UTF8)),
            )
            val message = receiving.await()
            assertTrue(
                message.qualityOfService.integerValue.toInt() <= 1,
                "[MQTT-3.8.4-6] delivery QoS must not exceed the granted subscription QoS",
            )
            client.shutdown()
        }

    @Test
    fun retainedMessageDeliveredToNewSubscriberThenCleared() =
        runTestNoTimeSkipping(timeout = 60.seconds) {
            if (TransportKind.TCP !in networkCapabilities().transports) return@runTestNoTimeSkipping
            val topic = PahoConformance.topic("v4retain")
            val (publisher, _) = startPahoClient(this, v4Request(PahoConformance.clientId("v4retainpub")))
            awaitPublishComplete(
                publisher.publish(topic, QualityOfService.AT_LEAST_ONCE, "kept".toReadBuffer(Charset.UTF8), retain = true),
            )

            // A subscriber arriving AFTER the publish must receive the retained copy.
            val (subscriber, _) = startPahoClient(this, v4Request(PahoConformance.clientId("v4retainsub")))
            val flow = subscriber.observe(TopicFilter.fromOrThrow(topic), OpaquePublishPayloadCodec)
            val receiving = async { expectMessage(flow) }
            subscriber.subscribe(topic, OpaquePublishPayloadCodec, maxQos = QualityOfService.AT_LEAST_ONCE).subAck.await()
            val retained = receiving.await()
            assertEquals("kept", retained.opaquePayloadUtf8())
            assertTrue(retained.retain, "[MQTT-3.3.1-8] retained delivery to a new subscription must set RETAIN=1")
            subscriber.shutdown()

            // A zero-byte retained publish clears the retained message ([MQTT-3.3.1-10]).
            awaitPublishComplete(
                publisher.publish(topic, QualityOfService.AT_LEAST_ONCE, null, retain = true),
            )
            val (verifier, _) = startPahoClient(this, v4Request(PahoConformance.clientId("v4retainver")))
            val verifyFlow = verifier.observe(TopicFilter.fromOrThrow(topic), OpaquePublishPayloadCodec)
            verifier.subscribe(topic, OpaquePublishPayloadCodec, maxQos = QualityOfService.AT_LEAST_ONCE).subAck.await()
            expectNoMessage(verifyFlow)
            verifier.shutdown()
            publisher.shutdown()
        }

    @Test
    fun wildcardFilterMatchesMultiLevel() =
        runTestNoTimeSkipping {
            if (TransportKind.TCP !in networkCapabilities().transports) return@runTestNoTimeSkipping
            val (client, _) = startPahoClient(this, v4Request(PahoConformance.clientId("v4wild")))
            val root = PahoConformance.topic("v4wild")
            val filter = "$root/#"
            val flow = client.observe(TopicFilter.fromOrThrow(filter), OpaquePublishPayloadCodec)
            val receiving = async { expectMessage(flow) }
            client.subscribe(filter, OpaquePublishPayloadCodec, maxQos = QualityOfService.AT_LEAST_ONCE).subAck.await()
            awaitPublishComplete(
                client.publish("$root/a/b", QualityOfService.AT_LEAST_ONCE, "wild".toReadBuffer(Charset.UTF8)),
            )
            val message = receiving.await()
            assertEquals("$root/a/b", message.topic.toString())
            assertEquals("wild", message.opaquePayloadUtf8())
            client.shutdown()
        }

    @Test
    fun willMessageDeliveredOnUngracefulDisconnect() =
        runTestNoTimeSkipping(timeout = 60.seconds) {
            if (TransportKind.TCP !in networkCapabilities().transports) return@runTestNoTimeSkipping
            val willTopic = PahoConformance.topic("v4will")
            val willRequest =
                ConnectionRequest(
                    variableHeader =
                        ConnectionRequest.VariableHeader(
                            cleanSession = true,
                            keepAliveSeconds = 15,
                            willFlag = true,
                            willQos = QualityOfService.AT_LEAST_ONCE,
                        ),
                    payload =
                        ConnectionRequest.Payload(
                            clientId = PahoConformance.clientId("v4willpub"),
                            willTopic = TopicName.fromOrThrow(willTopic),
                            willPayload = "gone".toReadBuffer(Charset.UTF8),
                        ),
                )
            val (dying, _) = startPahoClient(this, willRequest)
            val (watcher, _) = startPahoClient(this, v4Request(PahoConformance.clientId("v4willsub")))
            val flow = watcher.observe(TopicFilter.fromOrThrow(willTopic), OpaquePublishPayloadCodec)
            val receiving = async { expectMessage(flow, timeout = 20.seconds) }
            watcher.subscribe(willTopic, OpaquePublishPayloadCodec, maxQos = QualityOfService.AT_LEAST_ONCE).subAck.await()
            // Drop the connection without DISCONNECT: the broker must publish the will.
            dying.shutdown(sendDisconnect = false)
            val will = receiving.await()
            assertEquals("gone", will.opaquePayloadUtf8())
            watcher.shutdown()
        }

    @Test
    fun keepAlivePingExchange() =
        runTestNoTimeSkipping {
            if (TransportKind.TCP !in networkCapabilities().transports) return@runTestNoTimeSkipping
            val (client, _) =
                startPahoClient(this, v4Request(PahoConformance.clientId("v4ping"), keepAliveSeconds = 1))
            withTimeout(10.seconds) {
                while (client.pingResponseCount() < 2) {
                    delay(0.25.seconds)
                }
            }
            assertTrue(client.pingResponseCount() >= 2, "broker must answer PINGREQ with PINGRESP")
            client.shutdown()
        }

    @Test
    fun unsubscribeStopsDelivery() =
        runTestNoTimeSkipping {
            if (TransportKind.TCP !in networkCapabilities().transports) return@runTestNoTimeSkipping
            val (client, _) = startPahoClient(this, v4Request(PahoConformance.clientId("v4unsub")))
            val topic = PahoConformance.topic("v4unsub")
            val flow = client.observe(TopicFilter.fromOrThrow(topic), OpaquePublishPayloadCodec)
            val receiving = async { expectMessage(flow) }
            client.subscribe(topic, OpaquePublishPayloadCodec, maxQos = QualityOfService.AT_LEAST_ONCE).subAck.await()
            awaitPublishComplete(
                client.publish(topic, QualityOfService.AT_LEAST_ONCE, "before".toReadBuffer(Charset.UTF8)),
            )
            assertEquals("before", receiving.await().opaquePayloadUtf8())

            client.unsubscribe(topic).unsubAck.await()
            val afterFlow = client.observe(TopicFilter.fromOrThrow(topic), OpaquePublishPayloadCodec)
            awaitPublishComplete(
                client.publish(topic, QualityOfService.AT_LEAST_ONCE, "after".toReadBuffer(Charset.UTF8)),
            )
            expectNoMessage(afterFlow)
            client.shutdown()
        }

    @Test
    fun sessionResumesAcrossReconnect() =
        runTestNoTimeSkipping(timeout = 60.seconds) {
            if (TransportKind.TCP !in networkCapabilities().transports) return@runTestNoTimeSkipping
            val (client, _) =
                startPahoClient(
                    this,
                    v4Request(PahoConformance.clientId("v4resume"), cleanSession = false),
                )
            val topic = TopicName.fromOrThrow(PahoConformance.topic("v4resume"))
            // Full QoS flows before and after a broker-visible reconnect: the second
            // CONNACK exercises the sessionPresent=1 path ([MQTT-3.2.2-2]).
            sendAllMessageTypes(client, topic, "resume")
            client.sendDisconnect()
            client.awaitConnectivity()
            assertEquals(2L, client.connectionCount(), "client should have reconnected exactly once")
            sendAllMessageTypes(client, topic, "resume")
            client.shutdown()
        }
}
