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
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.random.nextUInt
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * End-to-end benchmark against a local Mosquitto broker (localhost:1883).
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
 * Run:
 *   ./gradlew :mqtt-client:jvmTest --tests "*EndToEndBenchmark*" --rerun
 *
 */
class EndToEndBenchmark {

    private val host = "localhost"
    private val port = 1883
    private val messageCount = 200

    private fun connectionOptions() = MqttConnectionOptions.SocketConnection(
        host, port, tlsEnabled = false, connectionTimeout = 10.seconds, readTimeout = 30.seconds,
    )

    private fun connectionRequest() = ConnectionRequest(
        variableHeader = ConnectionRequest.VariableHeader(cleanSession = true, keepAliveSeconds = 60),
        payload = ConnectionRequest.Payload(clientId = "bench-${Random.nextUInt()}"),
    )

    private suspend fun connectWithRetry(
        scope: CoroutineScope,
        broker: com.ditchoom.mqtt.connection.MqttBroker,
        persistence: InMemoryPersistence,
        factory: BufferFactory,
        maxAttempts: Int = 3,
    ): LocalMqttClient {
        var lastException: Exception? = null
        for (attempt in 1..maxAttempts) {
            try {
                return LocalMqttClient.connectOnce(scope, broker, persistence, factory)
            } catch (e: Exception) {
                lastException = e
                if (attempt < maxAttempts) {
                    delay(2000L * attempt)
                }
            }
        }
        throw lastException!!
    }

    private fun runSingle(
        label: String,
        factory: BufferFactory,
        qos: QualityOfService,
        payloadSize: Int,
        count: Int = messageCount,
    ): String = runBlocking(Dispatchers.Default) {
        // Pause between tests to let the broker release prior connections
        delay(1500)
        val topicStr = "bench/${Random.nextUInt()}"
        val topic = TopicName.fromOrThrow(topicStr)
        val filter = TopicFilter.fromOrThrow(topicStr)
        val persistence = InMemoryPersistence()
        val connReq = connectionRequest()
        val broker = persistence.addBroker(listOf(connectionOptions()), connReq)
        val scope = CoroutineScope(Dispatchers.Default)
        val client = connectWithRetry(scope, broker, persistence, factory)

        try {
            val received = AtomicInteger(0)
            val published = AtomicInteger(0)
            val allReceived = CompletableDeferred<Unit>()
            val handler = SubscriptionHandler.Blocking { _ ->
                if (received.incrementAndGet() >= count) {
                    allReceived.complete(Unit)
                }
            }
            val sub = connReq.controlPacketFactory.subscribe(filter, qos)
            client.subscribe(sub, handler).subAck.await()

            val payloadBytes = ByteArray(payloadSize) { (it % 256).toByte() }

            val mark = TimeSource.Monotonic.markNow()
            for (i in 0 until count) {
                try {
                    client.publish(
                        connReq.controlPacketFactory.publish(
                            topicName = topic, qos = qos, payload = BufferFactory.Default.wrap(payloadBytes),
                        ),
                    )
                    published.incrementAndGet()
                } catch (_: Exception) {
                    // Connection lost — stop publishing
                    break
                }
            }
            val actualCount = published.get()
            if (actualCount < count) {
                System.err.println("$label: Only published $actualCount/$count before connection loss")
            }
            withTimeout(60.seconds) { allReceived.await() }
            val elapsedMs = mark.elapsedNow().inWholeMilliseconds
            val opsPerSec = if (elapsedMs > 0) count.toLong() * 1000 / elapsedMs else count.toLong()
            val mbPerSec = if (elapsedMs > 0) count.toLong() * payloadSize / 1024.0 / 1024.0 * 1000 / elapsedMs else 0.0

            "$label: $count msgs in ${elapsedMs}ms = $opsPerSec msgs/s (${"%.1f".format(mbPerSec)} MB/s)"
        } finally {
            client.shutdown()
        }
    }

    @Test
    fun defaultQos0_64B() {
        println(runSingle("Default-QoS0-64B", BufferFactory.Default, QualityOfService.AT_MOST_ONCE, 64))
    }

    @Test
    fun defaultQos1_64B() {
        println(runSingle("Default-QoS1-64B", BufferFactory.Default, QualityOfService.AT_LEAST_ONCE, 64))
    }

    @Test
    fun managedQos0_64B() {
        println(runSingle("managed-QoS0-64B", BufferFactory.managed(), QualityOfService.AT_MOST_ONCE, 64))
    }

    @Test
    fun managedQos1_64B() {
        println(runSingle("managed-QoS1-64B", BufferFactory.managed(), QualityOfService.AT_LEAST_ONCE, 64))
    }

    @Test
    fun defaultQos0_4KB() {
        println(runSingle("Default-QoS0-4KB", BufferFactory.Default, QualityOfService.AT_MOST_ONCE, 4096))
    }

    @Test
    fun defaultQos1_4KB() {
        println(runSingle("Default-QoS1-4KB", BufferFactory.Default, QualityOfService.AT_LEAST_ONCE, 4096))
    }

    @Test
    fun managedQos1_4KB() {
        println(runSingle("managed-QoS1-4KB", BufferFactory.managed(), QualityOfService.AT_LEAST_ONCE, 4096))
    }

    @Test
    fun pooledDirectQos0_64B() {
        val pool = BufferPool()
        println(runSingle("Pooled-direct-QoS0-64B", BufferFactory.Default.withPooling(pool), QualityOfService.AT_MOST_ONCE, 64))
        pool.clear()
    }

    @Test
    fun pooledDirectQos1_64B() {
        val pool = BufferPool()
        println(runSingle("Pooled-direct-QoS1-64B", BufferFactory.Default.withPooling(pool), QualityOfService.AT_LEAST_ONCE, 64))
        pool.clear()
    }

    @Test
    fun pooledHeapQos0_64B() {
        val pool = BufferPool(factory = BufferFactory.managed())
        println(runSingle("Pooled-heap-QoS0-64B", BufferFactory.managed().withPooling(pool), QualityOfService.AT_MOST_ONCE, 64))
        pool.clear()
    }

    @Test
    fun pooledHeapQos1_64B() {
        val pool = BufferPool(factory = BufferFactory.managed())
        println(runSingle("Pooled-heap-QoS1-64B", BufferFactory.managed().withPooling(pool), QualityOfService.AT_LEAST_ONCE, 64))
        pool.clear()
    }

    @Test
    fun pooledDirectQos1_4KB() {
        val pool = BufferPool()
        println(runSingle("Pooled-direct-QoS1-4KB", BufferFactory.Default.withPooling(pool), QualityOfService.AT_LEAST_ONCE, 4096))
        pool.clear()
    }

    // ── 32KB payload ────────────────────────────────────────────────

    @Test
    fun defaultQos0_32KB() {
        println(runSingle("Default-QoS0-32KB", BufferFactory.Default, QualityOfService.AT_MOST_ONCE, 32_768))
    }

    @Test
    fun defaultQos1_32KB() {
        println(runSingle("Default-QoS1-32KB", BufferFactory.Default, QualityOfService.AT_LEAST_ONCE, 32_768))
    }

    @Test
    fun managedQos1_32KB() {
        println(runSingle("managed-QoS1-32KB", BufferFactory.managed(), QualityOfService.AT_LEAST_ONCE, 32_768))
    }

    @Test
    fun pooledDirectQos0_32KB() {
        val pool = BufferPool()
        println(runSingle("Pooled-direct-QoS0-32KB", BufferFactory.Default.withPooling(pool), QualityOfService.AT_MOST_ONCE, 32_768))
        pool.clear()
    }

    @Test
    fun pooledDirectQos1_32KB() {
        val pool = BufferPool()
        println(runSingle("Pooled-direct-QoS1-32KB", BufferFactory.Default.withPooling(pool), QualityOfService.AT_LEAST_ONCE, 32_768))
        pool.clear()
    }

    // ── 1MB payload ─────────────────────────────────────────────────

    @Test
    fun defaultQos1_1MB() {
        println(runSingle("Default-QoS1-1MB", BufferFactory.Default, QualityOfService.AT_LEAST_ONCE, 1_048_576, count = 50))
    }

    @Test
    fun managedQos1_1MB() {
        println(runSingle("managed-QoS1-1MB", BufferFactory.managed(), QualityOfService.AT_LEAST_ONCE, 1_048_576, count = 50))
    }

    @Test
    fun pooledDirectQos1_1MB() {
        val pool = BufferPool()
        println(runSingle("Pooled-direct-QoS1-1MB", BufferFactory.Default.withPooling(pool), QualityOfService.AT_LEAST_ONCE, 1_048_576, count = 50))
        pool.clear()
    }

    @Test
    fun pooledHeapQos1_1MB() {
        val pool = BufferPool(factory = BufferFactory.managed())
        println(runSingle("Pooled-heap-QoS1-1MB", BufferFactory.managed().withPooling(pool), QualityOfService.AT_LEAST_ONCE, 1_048_576, count = 50))
        pool.clear()
    }
}
