package com.ditchoom.mqtt.client.net

import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.codec.asReadBuffer
import com.ditchoom.mqtt.client.MqttClient
import com.ditchoom.mqtt.client.PublishResult
import com.ditchoom.mqtt.client.QoS1State
import com.ditchoom.mqtt.client.QoS2State
import com.ditchoom.mqtt.connection.MqttConnectionOptions
import com.ditchoom.mqtt.controlpacket.IConnectionAcknowledgment
import com.ditchoom.mqtt.controlpacket.IConnectionRequest
import com.ditchoom.mqtt.controlpacket.OpaquePublishPayload
import com.ditchoom.mqtt.controlpacket.PublishMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random
import kotlin.random.nextUInt
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Shared plumbing for [PahoConformanceV4Test] / [PahoConformanceV5Test]. The tests run
 * against the eclipse-paho conformance broker (paho.mqtt.testing `startbroker.py`,
 * MQTT 3.1.1 + 5.0 auto-detected on localhost:1883), which logs every MQTT spec
 * conformance statement `[MQTT-x.y.z-n]` it observes — the CI job surfaces that coverage
 * report. Gated behind -PconformanceTests; see build.gradle.kts and
 * .github/workflows/conformance.yaml.
 *
 * The broker keeps in-memory session/retained/will state for its whole lifetime, so every
 * test uses fresh random clientIds and topics.
 */
internal object PahoConformance {
    val host: String get() = if (getPlatform() == Platform.Android) "10.0.2.2" else "localhost"

    fun tcpOptions(): MqttConnectionOptions =
        MqttConnectionOptions.SocketConnection(
            host,
            1883,
            tlsEnabled = false,
            connectionTimeout = 10.seconds,
        )

    fun clientId(prefix: String): String = "$prefix-${Random.nextUInt()}"

    fun topic(prefix: String): String = "paho/$prefix/${Random.nextUInt()}"
}

/** Start a client against the conformance broker and await the CONNACK. */
internal suspend fun startPahoClient(
    scope: CoroutineScope,
    connectionRequest: IConnectionRequest,
): Pair<MqttClient, IConnectionAcknowledgment> {
    val persistence = connectionRequest.controlPacketFactory.defaultPersistence(true)
    val broker = persistence.addBroker(PahoConformance.tcpOptions(), connectionRequest)
    val client = MqttClient.start(scope, broker, persistence, connectSingle = createConnectFactory(broker))
    return client to client.awaitConnectivity()
}

/** Await broker acknowledgment of a QoS 1/2 publish (no-op for QoS 0). */
internal suspend fun awaitPublishComplete(result: PublishResult) {
    when (result) {
        is PublishResult.QoS1 -> result.state.first { it is QoS1State.Acknowledged }
        is PublishResult.QoS2 -> result.state.first { it is QoS2State.Complete }
        else -> Unit
    }
}

internal suspend fun expectMessage(
    flow: Flow<Pair<PublishMessage, OpaquePublishPayload>>,
    timeout: Duration = 10.seconds,
): PublishMessage = withTimeout(timeout) { flow.take(1).first().first }

internal suspend fun expectNoMessage(
    flow: Flow<Pair<PublishMessage, OpaquePublishPayload>>,
    window: Duration = 3.seconds,
) {
    val received = withTimeoutOrNull(window) { flow.take(1).first().first }
    assertNull(received, "expected no delivery within $window but received ${received?.topic}")
}

/** Materialize the opaque payload of a received PUBLISH as a UTF-8 string. */
internal fun PublishMessage.opaquePayloadUtf8(): String? {
    val typed =
        when (this) {
            is com.ditchoom.mqtt3.controlpacket.PublishMessageV4<*> -> payload
            is com.ditchoom.mqtt5.controlpacket.ControlPacketV5.Publish<*> -> payload
            else -> null
        }
    val handle = (typed as? OpaquePublishPayload)?.handle ?: return null
    val view = handle.asReadBuffer()
    return view.readString(view.remaining(), Charset.UTF8)
}
