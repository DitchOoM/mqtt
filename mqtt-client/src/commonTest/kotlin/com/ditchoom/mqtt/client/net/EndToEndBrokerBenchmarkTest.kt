package com.ditchoom.mqtt.client.net

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.managed
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.withPooling
import com.ditchoom.mqtt.InMemoryPersistence
import com.ditchoom.mqtt.client.LocalMqttClient
import com.ditchoom.mqtt.client.SubscriptionHandler
import com.ditchoom.mqtt.connection.MqttConnectionOptions
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.TopicFilter
import com.ditchoom.mqtt.controlpacket.TopicName
import com.ditchoom.mqtt3.controlpacket.ConnectionRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeout
import com.ditchoom.socket.NetworkCapabilities
import com.ditchoom.socket.getNetworkCapabilities
import kotlin.random.Random
import kotlin.random.nextUInt
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Cross-platform end-to-end benchmark against a local Mosquitto broker (localhost:1883).
 *
 * Measures real pub/sub throughput through a broker, comparing BufferFactory types.
 * Uses the handler-based subscribe API (SubscriptionHandler.Blocking) which runs
 * inline on the reader coroutine — no SharedFlow overhead.
 *
 * Start broker:
 *   docker run -d --name mqtt-bench -p 1883:1883 eclipse-mosquitto:2 \
 *     sh -c 'printf "listener 1883\nallow_anonymous true\n" > /mosquitto/config/mosquitto.conf && \
 *     exec mosquitto -c /mosquitto/config/mosquitto.conf'
 *
 * Run with -PintegrationTests:
 *   ./gradlew :mqtt-client:jvmTest -PintegrationTests --tests "*EndToEndBrokerBenchmarkTest*" --rerun
 *   ./gradlew :mqtt-client:jsNodeTest -PintegrationTests --tests "*EndToEndBrokerBenchmarkTest*" --rerun
 *   ./gradlew :mqtt-client:linuxX64Test -PintegrationTests --tests "*EndToEndBrokerBenchmarkTest*" --rerun
 */
class EndToEndBrokerBenchmarkTest {

    private val isAndroidDevice: Boolean = getPlatform() == Platform.Android
    private val host = if (isAndroidDevice) "10.0.2.2" else "localhost"
    private val port = 1883
    private val messageCount = 200

    private fun connectionOptions() = MqttConnectionOptions.SocketConnection(
        host, port, tls = false, connectionTimeout = 10.seconds, readTimeout = 30.seconds,
    )

    private fun connectionRequest() = ConnectionRequest(
        variableHeader = ConnectionRequest.VariableHeader(cleanSession = true, keepAliveSeconds = 60),
        payload = ConnectionRequest.Payload(clientId = "bench-${Random.nextUInt()}"),
    )

    private fun runSingle(
        label: String,
        factory: BufferFactory,
        qos: QualityOfService,
        payloadSize: Int,
        count: Int = messageCount,
    ): TestRunResult = runTestNoTimeSkipping(timeout = 120.seconds) {
        if (getNetworkCapabilities() != NetworkCapabilities.FULL_SOCKET_ACCESS) return@runTestNoTimeSkipping

        val topicStr = "bench/${Random.nextUInt()}"
        val topic = TopicName.fromOrThrow(topicStr)
        val filter = TopicFilter.fromOrThrow(topicStr)
        val persistence = InMemoryPersistence()
        val connReq = connectionRequest()
        val broker = persistence.addBroker(listOf(connectionOptions()), connReq)
        val client = LocalMqttClient.connectOnce(
            CoroutineScope(Dispatchers.Default), broker, persistence, factory,
        )

        try {
            val received = MutableStateFlow(0)
            val allReceived = CompletableDeferred<Unit>()
            val handler = SubscriptionHandler.Blocking { _ ->
                val newVal = received.value + 1
                received.value = newVal
                if (newVal >= count) {
                    allReceived.complete(Unit)
                }
            }
            val sub = connReq.controlPacketFactory.subscribe(filter, qos)
            client.subscribe(sub, handler).subAck.await()

            val payloadBytes = ByteArray(payloadSize) { (it % 256).toByte() }

            val mark = TimeSource.Monotonic.markNow()
            for (i in 0 until count) {
                client.publish(
                    connReq.controlPacketFactory.publish(
                        topicName = topic, qos = qos, payload = BufferFactory.Default.wrap(payloadBytes),
                    ),
                )
            }
            withTimeout(60.seconds) { allReceived.await() }
            val elapsedMs = mark.elapsedNow().inWholeMilliseconds
            val opsPerSec = if (elapsedMs > 0) count.toLong() * 1000 / elapsedMs else count.toLong()
            val totalBytes = count.toLong() * payloadSize
            val mbPerSec = if (elapsedMs > 0) totalBytes / 1024.0 / 1024.0 * 1000 / elapsedMs else 0.0

            val mbStr = ((mbPerSec * 10).toLong() / 10.0).toString()
            println("$label: $count msgs in ${elapsedMs}ms = $opsPerSec msgs/s ($mbStr MB/s)")
        } finally {
            client.shutdown()
        }
    }

    // ── QoS 0, 64B payload ──────────────────────────────────────────

    @Test
    fun defaultQos0_64B() = runSingle("Default-QoS0-64B", BufferFactory.Default, QualityOfService.AT_MOST_ONCE, 64)

    // ── QoS 1, 64B payload ──────────────────────────────────────────

    @Test
    fun defaultQos1_64B() = runSingle("Default-QoS1-64B", BufferFactory.Default, QualityOfService.AT_LEAST_ONCE, 64)

    @Test
    fun pooledQos1_64B(): TestRunResult {
        val pool = BufferPool()
        val result = runSingle("Pooled-QoS1-64B", BufferFactory.Default.withPooling(pool), QualityOfService.AT_LEAST_ONCE, 64)
        println("  Pool stats: ${pool.stats()}")
        pool.clear()
        return result
    }

    // ── QoS 0, 4KB payload ──────────────────────────────────────────

    @Test
    fun defaultQos0_4KB() = runSingle("Default-QoS0-4KB", BufferFactory.Default, QualityOfService.AT_MOST_ONCE, 4096)

    // ── QoS 1, 4KB payload ──────────────────────────────────────────

    @Test
    fun defaultQos1_4KB() = runSingle("Default-QoS1-4KB", BufferFactory.Default, QualityOfService.AT_LEAST_ONCE, 4096)

    @Test
    fun pooledQos1_4KB(): TestRunResult {
        val pool = BufferPool()
        val result = runSingle("Pooled-QoS1-4KB", BufferFactory.Default.withPooling(pool), QualityOfService.AT_LEAST_ONCE, 4096)
        println("  Pool stats: ${pool.stats()}")
        pool.clear()
        return result
    }

    // ── QoS 0, 32KB payload ─────────────────────────────────────────

    @Test
    fun defaultQos0_32KB() = runSingle("Default-QoS0-32KB", BufferFactory.Default, QualityOfService.AT_MOST_ONCE, 32_768)

    // ── QoS 1, 32KB payload ─────────────────────────────────────────

    @Test
    fun pooledQos1_32KB(): TestRunResult {
        val pool = BufferPool()
        val result = runSingle("Pooled-QoS1-32KB", BufferFactory.Default.withPooling(pool), QualityOfService.AT_LEAST_ONCE, 32_768)
        println("  Pool stats: ${pool.stats()}")
        pool.clear()
        return result
    }
}
