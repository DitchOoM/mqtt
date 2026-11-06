package com.ditchoom.mqtt.client.net

import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.codec.asReadBuffer
import com.ditchoom.buffer.toReadBuffer
import com.ditchoom.mqtt.InMemoryPersistence
import com.ditchoom.mqtt.client.MqttClient
import com.ditchoom.mqtt.client.MqttEndpoint
import com.ditchoom.mqtt.client.PublishResult
import com.ditchoom.mqtt.client.QoS1State
import com.ditchoom.mqtt.client.QoS2State
import com.ditchoom.mqtt.controlpacket.IConnectionRequest
import com.ditchoom.mqtt.controlpacket.OpaquePublishPayload
import com.ditchoom.mqtt.controlpacket.OpaquePublishPayloadCodec
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.TopicFilter
import com.ditchoom.socket.TlsConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
import kotlin.random.nextUInt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Real-broker interop for one (broker, protocol version, transport) cell of the CI matrix in
 * `.github/workflows/interop.yaml`. The cell comes from the environment, so the same class runs
 * against mosquitto, EMQX and HiveMQ, over TCP, TLS and WebSocket, as MQTT 3.1.1 and 5.
 *
 * Opt-in with `-PinteropTests`; see `InteropTarget` for the variables.
 */
class BrokerInteropTest {
    private val target = InteropTarget.fromEnvironment()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clients = mutableListOf<MqttClient>()

    @AfterTest
    fun shutdownClients() =
        runBlocking {
            clients.forEach { it.shutdown(sendDisconnect = true) }
            scope.cancel()
        }

    @Test
    fun publishesAndReceivesAtEveryQos() =
        interop {
            val topic = uniqueTopic("qos")
            val subscriber = connect("sub")
            val received = subscriber.collect(topic)
            subscriber.subscribeAndAwait(topic, QualityOfService.EXACTLY_ONCE)
            val publisher = connect("pub")

            for (qos in QualityOfService.entries) {
                publisher.publishAndAwait(topic, "qos-${qos.integerValue}", qos)
            }

            assertEquals(
                setOf("qos-0", "qos-1", "qos-2"),
                List(3) { received.receive() }.toSet(),
                "${target.label}: every QoS level is delivered",
            )
        }

    @Test
    fun retainedMessageReachesALateSubscriber() =
        interop {
            val topic = uniqueTopic("retained")
            connect("pub").publishAndAwait(topic, "kept", QualityOfService.AT_LEAST_ONCE, retain = true)

            val late = connect("late")
            val received = late.collect(topic)
            late.subscribeAndAwait(topic, QualityOfService.AT_LEAST_ONCE)

            assertEquals("kept", received.receive(), "${target.label}: the retained message is delivered on subscribe")
        }

    @Test
    fun resumedSessionKeepsItsSubscription() =
        interop {
            val topic = uniqueTopic("resume")
            val subscriber = connect("resume", persistentSession = true)
            val received = subscriber.collect(topic)
            subscriber.subscribeAndAwait(topic, QualityOfService.AT_LEAST_ONCE)

            subscriber.sendDisconnect()
            withTimeout(15.seconds) { while (subscriber.connectionCount() < 2) delay(50) }
            val resumed = subscriber.awaitConnectivity()
            assertTrue(resumed.sessionPresent, "${target.label}: the broker resumed the session")

            connect("pub").publishAndAwait(topic, "after-resume", QualityOfService.AT_LEAST_ONCE)
            assertEquals(
                "after-resume",
                received.receive(),
                "${target.label}: the resumed session still holds the subscription without resubscribing",
            )
        }

    @Test
    fun receiveMaximumBoundsInFlightPublishes() =
        interop {
            if (target.protocol != MqttProtocol.V5) return@interop
            val publisher = connect("rm")
            val receiveMaximum = publisher.awaitConnectivity().receiveMaximum
            assertTrue(receiveMaximum < BURST, "${target.label}: broker must advertise a Receive Maximum below $BURST")

            val topic = uniqueTopic("rm")
            val results =
                List(receiveMaximum * 5) { i ->
                    async { publisher.publishAndAwait(topic, "rm-$i", QualityOfService.AT_LEAST_ONCE) }
                }
            results.awaitAll()
            assertEquals(1L, publisher.connectionCount(), "${target.label}: the broker never disconnected the client")
        }

    /**
     * The #12 stress: many concurrent mixed QoS 1/2 publishes from one client, issued faster than
     * the send quota drains. Every publish must complete, every message must arrive intact, and the
     * connection must never drop (a broker disconnects a client that exceeds its Receive Maximum).
     */
    @Test
    fun concurrentMixedQosPublishesAllComplete() =
        interop {
            val topic = uniqueTopic("stress")
            val subscriber = connect("stress-sub")
            val received = subscriber.collect(topic)
            subscriber.subscribeAndAwait(topic, QualityOfService.EXACTLY_ONCE)
            val publisher = connect("stress-pub")

            List(BURST) { i ->
                val qos = if (i % 2 == 0) QualityOfService.AT_LEAST_ONCE else QualityOfService.EXACTLY_ONCE
                async { publisher.publishAndAwait(topic, "stress-$i", qos) }
            }.awaitAll()

            val payloads = List(BURST) { received.receive() }.toSet()
            assertEquals(List(BURST) { "stress-$it" }.toSet(), payloads, "${target.label}: every message arrived once")
            assertEquals(1L, publisher.connectionCount(), "${target.label}: the publisher never reconnected")
        }

    private fun interop(block: suspend CoroutineScope.() -> Unit) =
        runBlocking(Dispatchers.Default) { withTimeout(120.seconds) { block() } }

    private suspend fun connect(
        role: String,
        persistentSession: Boolean = false,
    ): MqttClient {
        val request = target.protocol.connectionRequest("interop-$role-${Random.nextUInt()}", persistentSession)
        val persistence = InMemoryPersistence()
        val client = MqttClient.start(scope, persistence.addBroker(request), persistence, target.endpoint())
        clients += client
        client.awaitConnectivity()
        return client
    }

    /** Starts collecting [topic] before anything is subscribed, so no delivery can be missed. */
    private fun MqttClient.collect(topic: String): Channel<String> {
        val channel = Channel<String>(Channel.UNLIMITED)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            observe(TopicFilter.fromOrThrow(topic), OpaquePublishPayloadCodec).collect { (_, payload) ->
                channel.send(payload.text())
            }
        }
        return channel
    }

    private suspend fun MqttClient.subscribeAndAwait(
        topic: String,
        qos: QualityOfService,
    ) {
        subscribe(packetFactory.subscribe(TopicFilter.fromOrThrow(topic), maximumQos = qos), OpaquePublishPayloadCodec)
            .subAck
            .await()
    }

    private suspend fun MqttClient.publishAndAwait(
        topic: String,
        text: String,
        qos: QualityOfService,
        retain: Boolean = false,
    ) {
        when (val result = publish(topic, qos, text.toReadBuffer(Charset.UTF8), retain)) {
            PublishResult.QoS0Sent -> Unit
            is PublishResult.QoS1 -> result.state.first { it is QoS1State.Acknowledged }
            is PublishResult.QoS2 -> result.state.first { it is QoS2State.Complete }
        }
    }

    private fun OpaquePublishPayload.text(): String {
        val buffer = handle.asReadBuffer()
        return buffer.readString(buffer.remaining(), Charset.UTF8)
    }

    private fun uniqueTopic(name: String): String = "ditchoom/interop/$name/${Random.nextUInt()}"

    private companion object {
        /** Well above every broker's configured Receive Maximum in interop.yaml (10). */
        const val BURST = 200
    }
}

/** The MQTT protocol version a matrix cell speaks. */
sealed interface MqttProtocol {
    fun connectionRequest(
        clientId: String,
        persistentSession: Boolean,
    ): IConnectionRequest

    data object V4 : MqttProtocol {
        override fun connectionRequest(
            clientId: String,
            persistentSession: Boolean,
        ): IConnectionRequest =
            com.ditchoom.mqtt3.controlpacket.ConnectionRequest(
                clientId = clientId,
                keepAliveSeconds = 30,
                cleanSession = !persistentSession,
            )
    }

    data object V5 : MqttProtocol {
        override fun connectionRequest(
            clientId: String,
            persistentSession: Boolean,
        ): IConnectionRequest =
            com.ditchoom.mqtt5.controlpacket.ConnectionRequest(
                clientId = clientId,
                keepAliveSeconds = 30,
                cleanStart = !persistentSession,
                props =
                    com.ditchoom.mqtt5.controlpacket.ConnectProperties(
                        sessionExpiryIntervalSeconds = if (persistentSession) 300uL else 0uL,
                    ),
            )
    }
}

/** How a matrix cell reaches the broker. */
sealed interface InteropTransport {
    fun endpoint(host: String): MqttEndpoint

    data class Tcp(
        val port: Int,
    ) : InteropTransport {
        override fun endpoint(host: String) = tcpEndpoint(host, port)
    }

    /** The broker presents CI's freshly generated self-signed certificate. */
    data class Tls(
        val port: Int,
    ) : InteropTransport {
        override fun endpoint(host: String) = tcpEndpoint(host, port, tls = SELF_SIGNED)
    }

    data class WebSocket(
        val port: Int,
        val path: String,
    ) : InteropTransport {
        override fun endpoint(host: String) = webSocketEndpoint(host, port, path = path)
    }

    private companion object {
        val SELF_SIGNED = TlsConfig(verifyCertificates = false, verifyHostname = false, allowSelfSigned = true)
    }
}

/**
 * One matrix cell, read once from the environment:
 * `MQTT_INTEROP_BROKER` (label), `MQTT_INTEROP_PROTOCOL` (`4` | `5`),
 * `MQTT_INTEROP_TRANSPORT` (`tcp` | `tls` | `ws`), `MQTT_INTEROP_PORT`, and for WebSocket
 * `MQTT_INTEROP_WS_PATH`. The host is always `localhost`, where CI publishes the broker's ports.
 */
data class InteropTarget(
    val label: String,
    val protocol: MqttProtocol,
    val transport: InteropTransport,
) {
    fun endpoint(): MqttEndpoint = transport.endpoint("localhost")

    companion object {
        fun fromEnvironment(): InteropTarget {
            fun env(name: String): String = checkNotNull(System.getenv(name)) { "$name is not set; see BrokerInteropTest" }
            val port = env("MQTT_INTEROP_PORT").toInt()
            val protocol =
                when (val version = env("MQTT_INTEROP_PROTOCOL")) {
                    "4" -> MqttProtocol.V4
                    "5" -> MqttProtocol.V5
                    else -> error("MQTT_INTEROP_PROTOCOL must be 4 or 5, was $version")
                }
            val transport =
                when (val name = env("MQTT_INTEROP_TRANSPORT")) {
                    "tcp" -> InteropTransport.Tcp(port)
                    "tls" -> InteropTransport.Tls(port)
                    "ws" -> InteropTransport.WebSocket(port, env("MQTT_INTEROP_WS_PATH"))
                    else -> error("MQTT_INTEROP_TRANSPORT must be tcp, tls or ws, was $name")
                }
            val label = "${env("MQTT_INTEROP_BROKER")} / MQTT ${env("MQTT_INTEROP_PROTOCOL")} / ${env("MQTT_INTEROP_TRANSPORT")}"
            return InteropTarget(label, protocol, transport)
        }
    }
}
