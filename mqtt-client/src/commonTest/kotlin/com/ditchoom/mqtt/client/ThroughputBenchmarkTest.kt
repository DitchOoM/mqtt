package com.ditchoom.mqtt.client

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.managed
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.withPooling
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.TopicName
import com.ditchoom.mqtt3.controlpacket.ControlPacketV4
import kotlin.test.Test
import kotlin.time.TimeSource
import com.ditchoom.mqtt3.controlpacket.ConnectionRequest as ConnectV4
import com.ditchoom.mqtt3.controlpacket.PublishMessage as PublishV4
import com.ditchoom.mqtt3.controlpacket.SubscribeRequest as SubscribeV4

/**
 * Cross-platform throughput benchmark for BufferFactory comparison.
 * Runs on JVM, JS (Node), and Linux native.
 * Measures ops/s for the write path and full round-trip.
 */
class ThroughputBenchmarkTest {
    private fun smallPackets(): List<ControlPacket> {
        val payload = BufferFactory.Default.allocate(64)
        repeat(64) { payload.writeByte((it % 256).toByte()) }
        payload.resetForRead()
        return listOf(
            ConnectV4(payload = ConnectV4.Payload(clientId = "xplat")),
            PublishV4
                .buildPayload(
                    topicName = TopicName.fromOrThrow("bench/topic"),
                    qos = QualityOfService.AT_LEAST_ONCE,
                    payload = payload,
                ).maybeCopyWithNewPacketIdentifier(1),
            SubscribeV4(packetIdentifier = 1.toUShort(), topic = "bench/+", qos = QualityOfService.AT_LEAST_ONCE),
        )
    }

    private fun largePayloadPacket(): ControlPacket {
        val payload = BufferFactory.Default.allocate(4096)
        repeat(4096) { payload.writeByte((it % 256).toByte()) }
        payload.resetForRead()
        return PublishV4
            .buildPayload(
                topicName = TopicName.fromOrThrow("bench/large"),
                qos = QualityOfService.EXACTLY_ONCE,
                payload = payload,
            ).maybeCopyWithNewPacketIdentifier(1)
    }

    data class RunResult(
        val label: String,
        val totalOps: Long,
        val elapsedMs: Long,
    ) {
        val opsPerSec: Long get() = if (elapsedMs > 0) totalOps * 1000 / elapsedMs else totalOps
    }

    private fun benchWritePath(
        label: String,
        factory: BufferFactory,
        packets: List<ControlPacket>,
        warmup: Int,
        iterations: Int,
    ): RunResult {
        repeat(warmup) {
            for (p in packets) {
                val buf = listOf(p).toBuffer(factory)
                buf.resetForWrite()
                buf.freeNativeMemory()
            }
        }

        val mark = TimeSource.Monotonic.markNow()
        repeat(iterations) {
            for (p in packets) {
                val buf = listOf(p).toBuffer(factory)
                buf.resetForWrite()
                buf.freeNativeMemory()
            }
        }
        val elapsed = mark.elapsedNow()
        return RunResult(label, iterations.toLong() * packets.size, elapsed.inWholeMilliseconds)
    }

    private fun benchRoundTrip(
        label: String,
        factory: BufferFactory,
        packets: List<ControlPacket>,
        warmup: Int,
        iterations: Int,
    ): RunResult {
        repeat(warmup) {
            for (p in packets) {
                val buf = listOf(p).toBuffer(factory)
                buf.resetForRead()
                ControlPacketV4.from(buf)
                buf.freeNativeMemory()
            }
        }

        val mark = TimeSource.Monotonic.markNow()
        repeat(iterations) {
            for (p in packets) {
                val buf = listOf(p).toBuffer(factory)
                buf.resetForRead()
                ControlPacketV4.from(buf)
                buf.freeNativeMemory()
            }
        }
        val elapsed = mark.elapsedNow()
        return RunResult(label, iterations.toLong() * packets.size, elapsed.inWholeMilliseconds)
    }

    private fun printResults(
        title: String,
        results: List<RunResult>,
    ) {
        println()
        println("═".repeat(70))
        println("  $title")
        println("═".repeat(70))
        println("${pad("Factory", 24)} ${pad("ops/s", 12)} ${pad("ms", 10)}")
        println("─".repeat(70))
        for (r in results) {
            println("${pad(r.label, 24)} ${pad(r.opsPerSec.toString(), 12)} ${pad(r.elapsedMs.toString(), 10)}")
        }
        println("═".repeat(70))
        println()
    }

    private fun pad(
        s: String,
        width: Int,
    ): String = if (s.length >= width) s else s + " ".repeat(width - s.length)

    // ── Tests ────────────────────────────────────────────────────────

    @Test
    fun writePathComparison() {
        val packets = smallPackets()
        val warmup = 2_000
        val iterations = 20_000

        val directPool = BufferPool()
        val heapPool = BufferPool(factory = BufferFactory.managed())

        val results =
            listOf(
                benchWritePath("Default", BufferFactory.Default, packets, warmup, iterations),
                benchWritePath("managed (heap)", BufferFactory.managed(), packets, warmup, iterations),
                benchWritePath("Pooled-direct", BufferFactory.Default.withPooling(directPool), packets, warmup, iterations),
                benchWritePath("Pooled-heap", BufferFactory.managed().withPooling(heapPool), packets, warmup, iterations),
            )

        printResults("WRITE PATH — small packets × $iterations", results)

        val stats = directPool.stats()
        println("  Pool-direct: hitRate=${((stats.hitRate * 1000).toLong() / 10.0)}%  peak=${stats.peakPoolSize}")
        val heapStats = heapPool.stats()
        println("  Pool-heap:   hitRate=${((heapStats.hitRate * 1000).toLong() / 10.0)}%  peak=${heapStats.peakPoolSize}")
        println()

        directPool.clear()
        heapPool.clear()
    }

    @Test
    fun roundTripComparison() {
        val packets = smallPackets()
        val warmup = 2_000
        val iterations = 20_000

        val directPool = BufferPool()
        val heapPool = BufferPool(factory = BufferFactory.managed())

        val results =
            listOf(
                benchRoundTrip("Default", BufferFactory.Default, packets, warmup, iterations),
                benchRoundTrip("managed (heap)", BufferFactory.managed(), packets, warmup, iterations),
                benchRoundTrip("Pooled-direct", BufferFactory.Default.withPooling(directPool), packets, warmup, iterations),
                benchRoundTrip("Pooled-heap", BufferFactory.managed().withPooling(heapPool), packets, warmup, iterations),
            )

        printResults("ROUND-TRIP — small packets × $iterations", results)

        directPool.clear()
        heapPool.clear()
    }

    @Test
    fun largePayloadComparison() {
        val packets = listOf(largePayloadPacket())
        val warmup = 1_000
        val iterations = 20_000

        val directPool = BufferPool()
        val heapPool = BufferPool(factory = BufferFactory.managed())

        val writeResults =
            listOf(
                benchWritePath("Default", BufferFactory.Default, packets, warmup, iterations),
                benchWritePath("managed (heap)", BufferFactory.managed(), packets, warmup, iterations),
                benchWritePath("Pooled-direct", BufferFactory.Default.withPooling(directPool), packets, warmup, iterations),
                benchWritePath("Pooled-heap", BufferFactory.managed().withPooling(heapPool), packets, warmup, iterations),
            )

        printResults("WRITE PATH — 4KB payload × $iterations", writeResults)

        directPool.clear()
        heapPool.clear()

        val directPool2 = BufferPool()
        val heapPool2 = BufferPool(factory = BufferFactory.managed())

        val rtResults =
            listOf(
                benchRoundTrip("Default", BufferFactory.Default, packets, warmup, iterations),
                benchRoundTrip("managed (heap)", BufferFactory.managed(), packets, warmup, iterations),
                benchRoundTrip("Pooled-direct", BufferFactory.Default.withPooling(directPool2), packets, warmup, iterations),
                benchRoundTrip("Pooled-heap", BufferFactory.managed().withPooling(heapPool2), packets, warmup, iterations),
            )

        printResults("ROUND-TRIP — 4KB payload × $iterations", rtResults)

        directPool2.clear()
        heapPool2.clear()
    }
}
