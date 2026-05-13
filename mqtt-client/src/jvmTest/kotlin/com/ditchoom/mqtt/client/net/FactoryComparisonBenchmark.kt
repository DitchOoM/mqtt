package com.ditchoom.mqtt.client.net

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.managed
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.pool.ThreadingMode
import com.ditchoom.buffer.withPooling
import com.ditchoom.mqtt.client.toBuffer
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.TopicName
import com.ditchoom.mqtt3.controlpacket.ControlPacketV4
import java.io.File
import java.lang.management.ManagementFactory
import javax.management.ObjectName
import kotlin.test.Test
import kotlin.time.measureTime
import com.ditchoom.mqtt3.controlpacket.ConnectionRequest as ConnectV4
import com.ditchoom.mqtt3.controlpacket.PublishMessageV4 as PublishV4
import com.ditchoom.mqtt3.controlpacket.SubscribeRequest as SubscribeV4

/**
 * Comparative benchmark across all BufferFactory types.
 *
 * Measures throughput (ops/s), memory (heap, direct buffers, RSS),
 * CPU time, and GC pressure for the MQTT write path
 * (toBuffer + freeNativeMemory) and full round-trip (serialize + decode).
 */
class FactoryComparisonBenchmark {
    // ── Metrics ──────────────────────────────────────────────────────

    data class MemSnapshot(
        val heapMB: Double,
        val directMB: Double,
        val directCount: Long,
        val rssMB: Double,
    )

    data class GcSnapshot(
        val count: Long,
        val timeMs: Long,
    )

    data class BenchResult(
        val label: String,
        val totalPackets: Long,
        val elapsedMs: Long,
        val opsPerSec: Long,
        val cpuMs: Long,
        val memBefore: MemSnapshot,
        val memAfter: MemSnapshot,
        val gcBefore: GcSnapshot,
        val gcAfter: GcSnapshot,
        val poolHitRate: Double?, // null if not pooled
        val poolPeakSize: Int?,
    )

    private fun snapshot(): MemSnapshot {
        repeat(3) {
            System.gc()
            Thread.sleep(30)
        }
        val runtime = Runtime.getRuntime()
        val heapMB = (runtime.totalMemory() - runtime.freeMemory()) / MB

        val (directCount, directBytes) =
            try {
                val mbs = ManagementFactory.getPlatformMBeanServer()
                val name = ObjectName("java.nio:type=BufferPool,name=direct")
                Pair(
                    mbs.getAttribute(name, "Count") as Long,
                    mbs.getAttribute(name, "MemoryUsed") as Long,
                )
            } catch (_: Exception) {
                Pair(-1L, -1L)
            }

        val rssMB =
            try {
                val status = File("/proc/self/status").readText()
                status
                    .lines()
                    .firstOrNull { it.startsWith("VmRSS:") }
                    ?.split("\\s+".toRegex())
                    ?.get(1)
                    ?.toLongOrNull()
                    ?.div(1024.0) ?: -1.0
            } catch (_: Exception) {
                -1.0
            }

        return MemSnapshot(heapMB, directBytes / MB, directCount, rssMB)
    }

    private fun gcSnapshot(): GcSnapshot {
        var count = 0L
        var timeMs = 0L
        for (bean in ManagementFactory.getGarbageCollectorMXBeans()) {
            if (bean.collectionCount >= 0) count += bean.collectionCount
            if (bean.collectionTime >= 0) timeMs += bean.collectionTime
        }
        return GcSnapshot(count, timeMs)
    }

    private fun threadCpuMs(): Long {
        val bean = ManagementFactory.getThreadMXBean()
        return if (bean.isCurrentThreadCpuTimeSupported) {
            bean.currentThreadCpuTime / 1_000_000
        } else {
            -1
        }
    }

    // ── Packet builders ──────────────────────────────────────────────

    private fun smallPackets(): List<ControlPacket> {
        val payload = BufferFactory.Default.allocate(64)
        repeat(64) { payload.writeByte((it % 256).toByte()) }
        payload.resetForRead()
        return listOf(
            ConnectV4(clientId = "bench"),
            PublishV4.ofRaw(
                topic = TopicName.fromOrThrow("bench/topic"),
                qos = QualityOfService.AT_LEAST_ONCE,
                payload = payload,
                packetIdentifier = 1,
            ),
            SubscribeV4(packetIdentifier = 1.toUShort(), topic = "bench/+", qos = QualityOfService.AT_LEAST_ONCE),
        )
    }

    private fun largePayloadPackets(): List<ControlPacket> {
        val payload = BufferFactory.Default.allocate(4096)
        repeat(4096) { payload.writeByte((it % 256).toByte()) }
        payload.resetForRead()
        return listOf(
            PublishV4.ofRaw(
                topic = TopicName.fromOrThrow("bench/large"),
                qos = QualityOfService.EXACTLY_ONCE,
                payload = payload,
                packetIdentifier = 1,
            ),
        )
    }

    // ── Benchmark harness ────────────────────────────────────────────

    private fun benchWritePath(
        label: String,
        factory: BufferFactory,
        packets: List<ControlPacket>,
        iterations: Int,
        pool: BufferPool? = null,
    ): BenchResult {
        // Warmup
        repeat(2_000) {
            for (p in packets) {
                val buf = listOf(p).toBuffer(factory) as PlatformBuffer
                buf.resetForWrite()
                buf.freeNativeMemory()
            }
        }

        val gcBefore = gcSnapshot()
        val memBefore = snapshot()
        val cpuBefore = threadCpuMs()

        val elapsed =
            measureTime {
                repeat(iterations) {
                    for (p in packets) {
                        val buf = listOf(p).toBuffer(factory) as PlatformBuffer
                        buf.resetForWrite()
                        buf.freeNativeMemory()
                    }
                }
            }

        val cpuAfter = threadCpuMs()
        val memAfter = snapshot()
        val gcAfter = gcSnapshot()

        val totalPackets = iterations.toLong() * packets.size
        val opsPerSec =
            if (elapsed.inWholeMilliseconds > 0) {
                totalPackets * 1000 / elapsed.inWholeMilliseconds
            } else {
                totalPackets
            }

        val stats = pool?.stats()

        return BenchResult(
            label = label,
            totalPackets = totalPackets,
            elapsedMs = elapsed.inWholeMilliseconds,
            opsPerSec = opsPerSec,
            cpuMs = cpuAfter - cpuBefore,
            memBefore = memBefore,
            memAfter = memAfter,
            gcBefore = gcBefore,
            gcAfter = gcAfter,
            poolHitRate = stats?.hitRate,
            poolPeakSize = stats?.peakPoolSize,
        )
    }

    private fun benchRoundTrip(
        label: String,
        factory: BufferFactory,
        packets: List<ControlPacket>,
        iterations: Int,
        pool: BufferPool? = null,
    ): BenchResult {
        // Warmup
        repeat(2_000) {
            for (p in packets) {
                val buf = listOf(p).toBuffer(factory) as PlatformBuffer
                ControlPacketV4.from(buf)
                buf.freeNativeMemory()
            }
        }

        val gcBefore = gcSnapshot()
        val memBefore = snapshot()
        val cpuBefore = threadCpuMs()

        val elapsed =
            measureTime {
                repeat(iterations) {
                    for (p in packets) {
                        val buf = listOf(p).toBuffer(factory) as PlatformBuffer
                        ControlPacketV4.from(buf)
                        buf.freeNativeMemory()
                    }
                }
            }

        val cpuAfter = threadCpuMs()
        val memAfter = snapshot()
        val gcAfter = gcSnapshot()

        val totalPackets = iterations.toLong() * packets.size
        val opsPerSec =
            if (elapsed.inWholeMilliseconds > 0) {
                totalPackets * 1000 / elapsed.inWholeMilliseconds
            } else {
                totalPackets
            }

        val stats = pool?.stats()

        return BenchResult(
            label = label,
            totalPackets = totalPackets,
            elapsedMs = elapsed.inWholeMilliseconds,
            opsPerSec = opsPerSec,
            cpuMs = cpuAfter - cpuBefore,
            memBefore = memBefore,
            memAfter = memAfter,
            gcBefore = gcBefore,
            gcAfter = gcAfter,
            poolHitRate = stats?.hitRate,
            poolPeakSize = stats?.peakPoolSize,
        )
    }

    // ── Reporting ────────────────────────────────────────────────────

    private fun printTable(
        title: String,
        results: List<BenchResult>,
    ) {
        println()
        println("═".repeat(120))
        println("  $title")
        println("═".repeat(120))
        println(
            "%-28s %10s %8s %8s %8s %8s %10s %10s %8s".format(
                "Factory",
                "ops/s",
                "ms",
                "cpu ms",
                "GC #",
                "GC ms",
                "direct Δ",
                "heap Δ MB",
                "pool hit",
            ),
        )
        println("─".repeat(120))

        for (r in results) {
            val gcCount = r.gcAfter.count - r.gcBefore.count
            val gcTime = r.gcAfter.timeMs - r.gcBefore.timeMs
            val directDelta = r.memAfter.directCount - r.memBefore.directCount
            val heapDelta = r.memAfter.heapMB - r.memBefore.heapMB
            val poolStr = r.poolHitRate?.let { "%.1f%%".format(it * 100) } ?: "n/a"

            println(
                "%-28s %,10d %,8d %,8d %,8d %,8d %+10d %+10.1f %8s".format(
                    r.label,
                    r.opsPerSec,
                    r.elapsedMs,
                    r.cpuMs,
                    gcCount,
                    gcTime,
                    directDelta,
                    heapDelta,
                    poolStr,
                ),
            )
        }
        println("═".repeat(120))
        println()
    }

    // ── Factory configs ──────────────────────────────────────────────

    data class FactoryConfig(
        val name: String,
        val factory: BufferFactory,
        val pool: BufferPool?,
    )

    private fun allFactories(): List<FactoryConfig> {
        val directPool = BufferPool(threadingMode = ThreadingMode.SingleThreaded)
        val heapPool = BufferPool(threadingMode = ThreadingMode.SingleThreaded, factory = BufferFactory.managed())

        return listOf(
            FactoryConfig("Default (direct)", BufferFactory.Default, null),
            FactoryConfig("managed (heap)", BufferFactory.managed(), null),
            FactoryConfig("Deterministic", BufferFactory.deterministic(), null),
            FactoryConfig("Pooled-direct", BufferFactory.Default.withPooling(directPool), directPool),
            FactoryConfig("Pooled-heap", BufferFactory.managed().withPooling(heapPool), heapPool),
        )
    }

    // ── Tests ────────────────────────────────────────────────────────

    @Test
    fun writePathSmallPackets() {
        val packets = smallPackets()
        val iterations = 50_000
        val results =
            allFactories().map { (name, factory, pool) ->
                pool?.clear()
                benchWritePath(name, factory, packets, iterations, pool)
            }
        printTable("WRITE PATH — small packets (CONNECT + PUBLISH-64B + SUBSCRIBE) × $iterations", results)
    }

    @Test
    fun writePathLargePayload() {
        val packets = largePayloadPackets()
        val iterations = 50_000
        val results =
            allFactories().map { (name, factory, pool) ->
                pool?.clear()
                benchWritePath(name, factory, packets, iterations, pool)
            }
        printTable("WRITE PATH — large payload (PUBLISH-4KB) × $iterations", results)
    }

    @Test
    fun roundTripSmallPackets() {
        val packets = smallPackets()
        val iterations = 50_000
        val results =
            allFactories().map { (name, factory, pool) ->
                pool?.clear()
                benchRoundTrip(name, factory, packets, iterations, pool)
            }
        printTable("ROUND-TRIP — small packets (serialize + decode) × $iterations", results)
    }

    @Test
    fun roundTripLargePayload() {
        val packets = largePayloadPackets()
        val iterations = 50_000
        val results =
            allFactories().map { (name, factory, pool) ->
                pool?.clear()
                benchRoundTrip(name, factory, packets, iterations, pool)
            }
        printTable("ROUND-TRIP — large payload (PUBLISH-4KB serialize + decode) × $iterations", results)
    }

    @Test
    fun scalingTest() {
        val packets = smallPackets()
        val directPool = BufferPool(threadingMode = ThreadingMode.SingleThreaded)
        val pooledFactory = BufferFactory.Default.withPooling(directPool)

        println()
        println("═".repeat(100))
        println("  SCALING — Default vs Pooled-direct at increasing iteration counts")
        println("═".repeat(100))
        println(
            "%-12s %12s %12s %12s %12s %10s %10s".format(
                "Iterations",
                "Default ops",
                "Pooled ops",
                "Default GC",
                "Pooled GC",
                "Def cpu",
                "Pool cpu",
            ),
        )
        println("─".repeat(100))

        for (scale in listOf(10_000, 50_000, 100_000, 200_000)) {
            directPool.clear()

            val rDefault = benchWritePath("Default", BufferFactory.Default, packets, scale, null)
            val rPooled = benchWritePath("Pooled", pooledFactory, packets, scale, directPool)

            val defaultGc = rDefault.gcAfter.count - rDefault.gcBefore.count
            val pooledGc = rPooled.gcAfter.count - rPooled.gcBefore.count

            println(
                "%,12d %,12d %,12d %,12d %,12d %,10d %,10d".format(
                    scale,
                    rDefault.opsPerSec,
                    rPooled.opsPerSec,
                    defaultGc,
                    pooledGc,
                    rDefault.cpuMs,
                    rPooled.cpuMs,
                ),
            )
        }
        println("═".repeat(100))
        println()

        directPool.clear()
    }

    @Test
    fun memoryFootprintOverTime() {
        val packets = smallPackets()
        val directPool = BufferPool(threadingMode = ThreadingMode.SingleThreaded)
        val pooledFactory = BufferFactory.Default.withPooling(directPool)

        // Warmup both
        repeat(5_000) {
            for (p in packets) {
                val b1 = listOf(p).toBuffer(BufferFactory.Default) as PlatformBuffer
                b1.freeNativeMemory()
                val b2 = listOf(p).toBuffer(pooledFactory) as PlatformBuffer
                b2.freeNativeMemory()
            }
        }

        println()
        println("═".repeat(110))
        println("  MEMORY FOOTPRINT — cumulative snapshot every 25K iterations")
        println("═".repeat(110))
        println(
            "%-10s %-14s %10s %10s %10s %10s %10s".format(
                "Iter (K)",
                "Factory",
                "direct #",
                "direct MB",
                "heap MB",
                "RSS MB",
                "pool peak",
            ),
        )
        println("─".repeat(110))

        for (step in 1..8) {
            val iters = 25_000

            // Default
            repeat(iters) {
                for (p in packets) {
                    val buf = listOf(p).toBuffer(BufferFactory.Default) as PlatformBuffer
                    buf.resetForWrite()
                    buf.freeNativeMemory()
                }
            }
            val snapDefault = snapshot()

            // Pooled
            repeat(iters) {
                for (p in packets) {
                    val buf = listOf(p).toBuffer(pooledFactory) as PlatformBuffer
                    buf.resetForWrite()
                    buf.freeNativeMemory()
                }
            }
            val snapPooled = snapshot()
            val stats = directPool.stats()

            val totalK = step * 25

            println(
                "%-10s %-14s %,10d %10.2f %10.1f %10.1f %10s".format(
                    "${totalK}K",
                    "Default",
                    snapDefault.directCount,
                    snapDefault.directMB,
                    snapDefault.heapMB,
                    snapDefault.rssMB,
                    "n/a",
                ),
            )
            println(
                "%-10s %-14s %,10d %10.2f %10.1f %10.1f %,10d".format(
                    "${totalK}K",
                    "Pooled-direct",
                    snapPooled.directCount,
                    snapPooled.directMB,
                    snapPooled.heapMB,
                    snapPooled.rssMB,
                    stats.peakPoolSize,
                ),
            )
        }
        println("═".repeat(110))
        println()

        directPool.clear()
    }

    companion object {
        private const val MB = 1024.0 * 1024.0
    }
}
