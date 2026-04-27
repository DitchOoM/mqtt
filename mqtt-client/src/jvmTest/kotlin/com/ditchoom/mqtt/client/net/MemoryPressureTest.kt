package com.ditchoom.mqtt.client.net

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.deterministic
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.stream.builder
import com.ditchoom.buffer.withPooling
import com.ditchoom.mqtt.client.mqttPeekFrameSize
import com.ditchoom.mqtt.client.toBuffer
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.TopicName
import com.ditchoom.mqtt3.controlpacket.ControlPacketV4
import com.ditchoom.mqtt5.controlpacket.ControlPacketV5
import com.ditchoom.mqtt5.controlpacket.ControlPacketV5Factory
import java.io.File
import java.lang.management.ManagementFactory
import javax.management.ObjectName
import kotlin.test.Test
import kotlin.test.assertTrue
import com.ditchoom.mqtt3.controlpacket.ConnectionRequest as ConnectV4
import com.ditchoom.mqtt3.controlpacket.PublishMessageV4 as PublishV4
import com.ditchoom.mqtt3.controlpacket.SubscribeRequest as SubscribeV4
import com.ditchoom.mqtt5.controlpacket.ConnectionRequest as ConnectV5
import com.ditchoom.mqtt5.controlpacket.ControlPacketV5.Publish as PublishV5
import com.ditchoom.mqtt5.controlpacket.SubscribeRequest as SubscribeV5

/**
 * Memory pressure tests for MQTT packet serialization/deserialization.
 * Tracks both JVM heap, native/direct memory, and OS RSS to detect leaks
 * in the codec paths that use DirectByteBuffer (off-heap) allocations.
 */
class MemoryPressureTest {
    // --- Memory measurement ---

    data class MemSnapshot(
        val heapMB: Double,
        val directMB: Double,
        val directCount: Long,
        val rssMB: Double,
    ) {
        override fun toString() = "heap=%.1fMB  direct=%.2fMB(%d bufs)  rss=%.1fMB".format(heapMB, directMB, directCount, rssMB)
    }

    private fun snapshot(): MemSnapshot {
        repeat(3) {
            System.gc()
            Thread.sleep(50)
        }
        val runtime = Runtime.getRuntime()
        val heapMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024.0 * 1024.0)

        // Direct ByteBuffer pool via JMX (the real metric, not NON_HEAP which includes metaspace/JIT)
        val (directCount, directBytes) =
            try {
                val mbs = ManagementFactory.getPlatformMBeanServer()
                val name = ObjectName("java.nio:type=BufferPool,name=direct")
                val count = mbs.getAttribute(name, "Count") as Long
                val memUsed = mbs.getAttribute(name, "MemoryUsed") as Long
                Pair(count, memUsed)
            } catch (_: Exception) {
                Pair(-1L, -1L)
            }
        val directMB = directBytes / (1024.0 * 1024.0)

        // OS-level RSS from /proc/self/status (Linux)
        val rssMB =
            try {
                val status = File("/proc/self/status").readText()
                val vmRss = status.lines().firstOrNull { it.startsWith("VmRSS:") }
                vmRss
                    ?.split("\\s+".toRegex())
                    ?.get(1)
                    ?.toLongOrNull()
                    ?.div(1024.0) ?: -1.0
            } catch (_: Exception) {
                -1.0
            }

        return MemSnapshot(heapMB, directMB, directCount, rssMB)
    }

    // --- V4 packet builders ---

    private fun buildV4Connect(): ControlPacket = ConnectV4(payload = ConnectV4.Payload(clientId = "pressure-test-client"))

    private fun buildV4Publish(id: Int): ControlPacket {
        val payload = BufferFactory.Default.allocate(128)
        repeat(128) { payload.writeByte((it % 256).toByte()) }
        payload.resetForRead()
        return PublishV4.ofRaw(
            topic = TopicName.fromOrThrow("pressure/test/topic"),
            qos = QualityOfService.AT_LEAST_ONCE,
            payload = payload,
            packetIdentifier = id,
        )
    }

    private fun buildV4Subscribe(): ControlPacket =
        SubscribeV4(
            packetIdentifier = 1.toUShort(),
            topic = "pressure/+/topic",
            qos = QualityOfService.AT_LEAST_ONCE,
        )

    // --- V5 packet builders ---

    private fun buildV5Connect(): ControlPacket = ConnectV5(clientId = "pressure-test-v5-client")

    private fun buildV5Publish(id: Int): ControlPacket =
        PublishV5.ofRaw(
            topic = TopicName.fromOrThrow("pressure/test/topic"),
            qos = QualityOfService.AT_LEAST_ONCE,
            packetIdentifier = id,
        )

    private fun buildV5Subscribe(): ControlPacket =
        SubscribeV5(
            packetIdentifier = 1.toUShort(),
            topic = "pressure/+/topic",
            qos = QualityOfService.AT_LEAST_ONCE,
        )

    // --- Core round-trip logic ---

    private fun roundTripDirect(
        packets: List<ControlPacket>,
        iterations: Int,
        decode: (ReadBuffer) -> ControlPacket,
    ) {
        repeat(iterations) {
            for (packet in packets) {
                val buf = packet.serialize()
                decode(buf)
            }
        }
    }

    private fun roundTripWithPool(
        packets: List<ControlPacket>,
        iterations: Int,
        decode: (ReadBuffer) -> ControlPacket,
    ) {
        val pool = BufferPool()
        val stream = StreamProcessor.builder(pool).build()

        repeat(iterations) {
            for (packet in packets) {
                val serialized = packet.serialize()
                stream.append(serialized)
                val frameSize =
                    mqttPeekFrameSize(stream, 0)
                        ?: error("frame underflow")
                stream.readBufferScoped(frameSize) { decode(this) }
            }
        }

        stream.release()
    }

    private fun assertNoLeak(
        label: String,
        before: MemSnapshot,
        after: MemSnapshot,
        maxRssGrowthMB: Double = 100.0,
    ) {
        val rssGrowth = after.rssMB - before.rssMB
        println("[$label] before: $before")
        println("[$label] after:  $after")
        println(
            "[$label] delta:  heap=%+.1fMB  direct=%+.2fMB(%+d bufs)  rss=%+.1fMB".format(
                after.heapMB - before.heapMB,
                after.directMB - before.directMB,
                after.directCount - before.directCount,
                rssGrowth,
            ),
        )
        if (before.rssMB > 0 && after.rssMB > 0) {
            assertTrue(
                rssGrowth < maxRssGrowthMB,
                "[$label] RSS grew %.1fMB (%.1f → %.1f) — possible native memory leak".format(
                    rssGrowth,
                    before.rssMB,
                    after.rssMB,
                ),
            )
        }
    }

    // --- Tests ---

    @Test
    fun v4DirectRoundTripNoLeak() {
        val packets = listOf(buildV4Connect(), buildV4Publish(1), buildV4Publish(2), buildV4Subscribe())
        val iterations = 50_000

        roundTripDirect(packets, 1000) { ControlPacketV4.from(it) }
        val before = snapshot()
        roundTripDirect(packets, iterations) { ControlPacketV4.from(it) }
        val after = snapshot()

        assertNoLeak("v4-direct ${iterations * packets.size} packets", before, after)
    }

    @Test
    fun v5DirectRoundTripNoLeak() {
        val packets = listOf(buildV5Connect(), buildV5Publish(1), buildV5Publish(2), buildV5Subscribe())
        val iterations = 50_000

        roundTripDirect(packets, 1000) { ControlPacketV5.from(it) }
        val before = snapshot()
        roundTripDirect(packets, iterations) { ControlPacketV5.from(it) }
        val after = snapshot()

        assertNoLeak("v5-direct ${iterations * packets.size} packets", before, after)
    }

    @Test
    fun v4PooledStreamProcessorNoLeak() {
        val packets = listOf(buildV4Connect(), buildV4Publish(1), buildV4Publish(2), buildV4Subscribe())
        val iterations = 50_000

        roundTripWithPool(packets, 1000) { ControlPacketV4.from(it) }
        val before = snapshot()
        roundTripWithPool(packets, iterations) { ControlPacketV4.from(it) }
        val after = snapshot()

        assertNoLeak("v4-pooled ${iterations * packets.size} packets", before, after)
    }

    @Test
    fun v5PooledStreamProcessorNoLeak() {
        val packets = listOf(buildV5Connect(), buildV5Publish(1), buildV5Publish(2), buildV5Subscribe())
        val iterations = 50_000

        roundTripWithPool(packets, 1000) { ControlPacketV5Factory.from(it) }
        val before = snapshot()
        roundTripWithPool(packets, iterations) { ControlPacketV5Factory.from(it) }
        val after = snapshot()

        assertNoLeak("v5-pooled ${iterations * packets.size} packets", before, after)
    }

    @Test
    fun largePayloadPressure() {
        val iterations = 10_000

        // Warmup
        repeat(100) {
            val payload = BufferFactory.Default.allocate(4096)
            repeat(4096) { i -> payload.writeByte((i % 256).toByte()) }
            payload.resetForRead()
            val pub =
                PublishV4.ofRaw(
                    topic = TopicName.fromOrThrow("pressure/large"),
                    qos = QualityOfService.AT_LEAST_ONCE,
                    payload = payload,
                    packetIdentifier = 1,
                )
            val buf = pub.serialize()
            ControlPacketV4.from(buf)
        }

        val before = snapshot()

        repeat(iterations) { i ->
            val payload = BufferFactory.Default.allocate(4096)
            repeat(4096) { j -> payload.writeByte((j % 256).toByte()) }
            payload.resetForRead()
            val pub =
                PublishV4.ofRaw(
                    topic = TopicName.fromOrThrow("pressure/large"),
                    qos = QualityOfService.AT_LEAST_ONCE,
                    payload = payload,
                    packetIdentifier = i % 65535 + 1,
                )
            val buf = pub.serialize()
            ControlPacketV4.from(buf)
        }

        val after = snapshot()
        assertNoLeak("large-payload ${iterations}x4KB", before, after)
    }

    @Test
    fun poolReuseMeasurement() {
        val pool = BufferPool()
        val iterations = 50_000

        // Warmup
        repeat(1000) {
            val buf = pool.acquire(256)
            repeat(128) { i -> buf.writeByte((i % 256).toByte()) }
            buf.resetForRead()
            pool.release(buf)
        }

        val before = snapshot()

        repeat(iterations) {
            val buf = pool.acquire(256)
            repeat(128) { i -> buf.writeByte((i % 256).toByte()) }
            buf.resetForRead()
            pool.release(buf)
        }

        val after = snapshot()
        val stats = pool.stats()

        assertNoLeak("pool-reuse $iterations cycles", before, after, maxRssGrowthMB = 20.0)
        println(
            "[pool-reuse] hitRate=%.1f%%  poolSize=%d  peak=%d".format(
                stats.hitRate * 100,
                stats.currentPoolSize,
                stats.peakPoolSize,
            ),
        )
        assertTrue(stats.hitRate > 0.9, "Pool hit rate %.1f%% — buffers not reused".format(stats.hitRate * 100))

        pool.clear()
    }

    @Test
    fun pooledFactoryWritePath() {
        val pool = BufferPool()
        val factory = BufferFactory.Default.withPooling(pool)
        val packets = listOf(buildV4Connect(), buildV4Publish(1), buildV4Subscribe())
        val iterations = 50_000

        // Warmup
        repeat(1000) {
            for (packet in packets) {
                val buf = listOf(packet).toBuffer(factory) as PlatformBuffer
                buf.resetForWrite()
                buf.freeNativeMemory()
            }
        }

        val before = snapshot()

        repeat(iterations) {
            for (packet in packets) {
                val buf = listOf(packet).toBuffer(factory) as PlatformBuffer
                buf.resetForWrite()
                buf.freeNativeMemory()
            }
        }

        val after = snapshot()
        val stats = pool.stats()

        assertNoLeak("pooled-factory-write ${iterations * packets.size} packets", before, after, maxRssGrowthMB = 20.0)
        println(
            "[pooled-factory-write] hitRate=%.1f%%  poolSize=%d  peak=%d".format(
                stats.hitRate * 100,
                stats.currentPoolSize,
                stats.peakPoolSize,
            ),
        )
        assertTrue(stats.hitRate > 0.9, "Pool hit rate %.1f%% — buffers not reused".format(stats.hitRate * 100))

        pool.clear()
    }

    @Test
    fun bufferFreeingAfterUse() {
        val packets = listOf(buildV4Connect(), buildV4Publish(1), buildV4Subscribe())
        val iterations = 50_000

        // Warmup
        repeat(1000) {
            for (packet in packets) {
                val buf = packet.serialize() as PlatformBuffer
                buf.freeNativeMemory()
            }
        }

        val before = snapshot()

        repeat(iterations) {
            for (packet in packets) {
                val buf = packet.serialize() as PlatformBuffer
                buf.freeNativeMemory()
            }
        }

        val after = snapshot()
        assertNoLeak("buffer-freeing ${iterations * packets.size} packets", before, after)
    }

    @Test
    fun deterministicFactoryCleanup() {
        val factory = BufferFactory.deterministic()
        val packets = listOf(buildV4Connect(), buildV4Publish(1), buildV4Subscribe())
        val iterations = 50_000

        // Warmup
        repeat(1000) {
            for (packet in packets) {
                val buf = listOf(packet).toBuffer(factory) as PlatformBuffer
                ControlPacketV4.from(buf)
                buf.freeNativeMemory()
            }
        }

        val before = snapshot()

        repeat(iterations) {
            for (packet in packets) {
                val buf = listOf(packet).toBuffer(factory) as PlatformBuffer
                ControlPacketV4.from(buf)
                buf.freeNativeMemory()
            }
        }

        val after = snapshot()
        assertNoLeak("deterministic-factory ${iterations * packets.size} packets", before, after, maxRssGrowthMB = 20.0)
    }
}
