package com.ditchoom.mqtt.client.net

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.stream.builder
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.TopicName
import com.ditchoom.mqtt3.controlpacket.ControlPacketV4
import com.ditchoom.mqtt5.controlpacket.ControlPacketV5
import java.io.File
import java.lang.management.ManagementFactory
import javax.management.ObjectName
import kotlin.test.Test
import kotlin.time.measureTime
import com.ditchoom.mqtt3.controlpacket.ConnectionRequest as ConnectV4
import com.ditchoom.mqtt3.controlpacket.PublishMessageV4 as PublishV4
import com.ditchoom.mqtt3.controlpacket.SubscribeRequest as SubscribeV4
import com.ditchoom.mqtt5.controlpacket.ConnectionRequest as ConnectV5
import com.ditchoom.mqtt5.controlpacket.ControlPacketV5.Publish as PublishV5
import com.ditchoom.mqtt5.controlpacket.SubscribeRequest as SubscribeV5

/**
 * Before/after benchmark for the factory threading + buffer freeing changes.
 * Tests that exist in both old and new code so we can compare.
 */
class BeforeAfterBenchmark {
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

    // --- Packet builders ---

    private fun v4Packets(): List<ControlPacket> {
        val payload = BufferFactory.Default.allocate(128)
        repeat(128) { payload.writeByte((it % 256).toByte()) }
        payload.resetForRead()
        return listOf(
            ConnectV4(payload = ConnectV4.Payload(clientId = "bench-v4")),
            PublishV4.ofRaw(
                topic = TopicName.fromOrThrow("bench/topic"),
                qos = QualityOfService.AT_LEAST_ONCE,
                payload = payload,
                packetIdentifier = 1,
            ),
            SubscribeV4(packetIdentifier = 1.toUShort(), topic = "bench/+", qos = QualityOfService.AT_LEAST_ONCE),
        )
    }

    private fun v5Packets(): List<ControlPacket> =
        listOf(
            ConnectV5(clientId = "bench-v5"),
            PublishV5.ofRaw(
                topic = TopicName.fromOrThrow("bench/topic"),
                qos = QualityOfService.AT_LEAST_ONCE,
                packetIdentifier = 1,
            ),
            SubscribeV5(packetIdentifier = 1.toUShort(), topic = "bench/+", qos = QualityOfService.AT_LEAST_ONCE),
        )

    // --- Benchmarks ---

    @Test
    fun v4SerializeDeserializeThroughput() {
        val packets = v4Packets()
        val warmupIters = 5_000
        val benchIters = 50_000

        // Warmup
        repeat(warmupIters) {
            for (p in packets) {
                val buf = p.serialize()
                ControlPacketV4.from(buf)
            }
        }

        val before = snapshot()
        val elapsed =
            measureTime {
                repeat(benchIters) {
                    for (p in packets) {
                        val buf = p.serialize()
                        ControlPacketV4.from(buf)
                    }
                }
            }
        val after = snapshot()
        val totalPackets = benchIters.toLong() * packets.size
        val opsPerSec = totalPackets / elapsed.inWholeMilliseconds.toDouble() * 1000

        println("[v4-serde] $totalPackets packets in ${elapsed.inWholeMilliseconds}ms = %.0f ops/s".format(opsPerSec))
        println("[v4-serde] before: $before")
        println("[v4-serde] after:  $after")
        println(
            "[v4-serde] delta:  direct=%+d bufs  rss=%+.1fMB".format(
                after.directCount - before.directCount,
                after.rssMB - before.rssMB,
            ),
        )
    }

    @Test
    fun v5SerializeDeserializeThroughput() {
        val packets = v5Packets()
        val warmupIters = 5_000
        val benchIters = 50_000

        // Warmup
        repeat(warmupIters) {
            for (p in packets) {
                val buf = p.serialize()
                ControlPacketV5.from(buf)
            }
        }

        val before = snapshot()
        val elapsed =
            measureTime {
                repeat(benchIters) {
                    for (p in packets) {
                        val buf = p.serialize()
                        ControlPacketV5.from(buf)
                    }
                }
            }
        val after = snapshot()
        val totalPackets = benchIters.toLong() * packets.size
        val opsPerSec = totalPackets / elapsed.inWholeMilliseconds.toDouble() * 1000

        println("[v5-serde] $totalPackets packets in ${elapsed.inWholeMilliseconds}ms = %.0f ops/s".format(opsPerSec))
        println("[v5-serde] before: $before")
        println("[v5-serde] after:  $after")
        println(
            "[v5-serde] delta:  direct=%+d bufs  rss=%+.1fMB".format(
                after.directCount - before.directCount,
                after.rssMB - before.rssMB,
            ),
        )
    }

    @Test
    fun v5PooledStreamProcessorThroughput() {
        val packets = v5Packets()
        val warmupIters = 5_000
        val benchIters = 50_000

        fun runPooled(iterations: Int) {
            val pool = BufferPool()
            val stream = StreamProcessor.builder(pool).build()
            repeat(iterations) {
                for (p in packets) {
                    val serialized = p.serialize()
                    stream.append(serialized)
                    val byte1 = stream.readUnsignedByte().toUByte()
                    val remainingLength = readVarInt(stream)
                    val body = if (remainingLength > 0) stream.readBuffer(remainingLength) else ReadBuffer.EMPTY_BUFFER
                    ControlPacketV5.from(body, byte1, remainingLength)
                }
            }
            stream.release()
        }

        runPooled(warmupIters)

        val before = snapshot()
        val elapsed = measureTime { runPooled(benchIters) }
        val after = snapshot()
        val totalPackets = benchIters.toLong() * packets.size
        val opsPerSec = totalPackets / elapsed.inWholeMilliseconds.toDouble() * 1000

        println("[v5-pooled-stream] $totalPackets packets in ${elapsed.inWholeMilliseconds}ms = %.0f ops/s".format(opsPerSec))
        println("[v5-pooled-stream] before: $before")
        println("[v5-pooled-stream] after:  $after")
        println(
            "[v5-pooled-stream] delta:  direct=%+d bufs  rss=%+.1fMB".format(
                after.directCount - before.directCount,
                after.rssMB - before.rssMB,
            ),
        )
    }

    @Test
    fun v4PooledStreamProcessorThroughput() {
        val packets = v4Packets()
        val warmupIters = 5_000
        val benchIters = 50_000

        fun runPooled(iterations: Int) {
            val pool = BufferPool()
            val stream = StreamProcessor.builder(pool).build()
            repeat(iterations) {
                for (p in packets) {
                    val serialized = p.serialize()
                    stream.append(serialized)
                    val byte1 = stream.readUnsignedByte().toUByte()
                    val remainingLength = readVarInt(stream)
                    val body = if (remainingLength > 0) stream.readBuffer(remainingLength) else ReadBuffer.EMPTY_BUFFER
                    ControlPacketV4.from(body, byte1, remainingLength)
                }
            }
            stream.release()
        }

        runPooled(warmupIters)

        val before = snapshot()
        val elapsed = measureTime { runPooled(benchIters) }
        val after = snapshot()
        val totalPackets = benchIters.toLong() * packets.size
        val opsPerSec = totalPackets / elapsed.inWholeMilliseconds.toDouble() * 1000

        println("[v4-pooled-stream] $totalPackets packets in ${elapsed.inWholeMilliseconds}ms = %.0f ops/s".format(opsPerSec))
        println("[v4-pooled-stream] before: $before")
        println("[v4-pooled-stream] after:  $after")
        println(
            "[v4-pooled-stream] delta:  direct=%+d bufs  rss=%+.1fMB".format(
                after.directCount - before.directCount,
                after.rssMB - before.rssMB,
            ),
        )
    }

    @Test
    fun v5ZeroBodyPacketAllocation() {
        // Specifically tests PINGREQ/PINGRESP deserialization (remainingLength=0)
        // Before: allocates BufferFactory.Default.allocate(0) each time
        // After: uses ReadBuffer.EMPTY_BUFFER singleton
        val warmupIters = 10_000
        val benchIters = 200_000

        fun deserializePingPair(iterations: Int) {
            // PINGREQ = 0xC0 0x00, PINGRESP = 0xD0 0x00
            repeat(iterations) {
                val buf = BufferFactory.Default.allocate(4)
                buf.writeByte(0xC0.toByte()) // PINGREQ fixed header
                buf.writeByte(0x00) // remaining length = 0
                buf.writeByte(0xD0.toByte()) // PINGRESP fixed header
                buf.writeByte(0x00) // remaining length = 0
                buf.resetForRead()
                ControlPacketV5.from(buf)
                ControlPacketV5.from(buf)
            }
        }

        deserializePingPair(warmupIters)

        val before = snapshot()
        val elapsed = measureTime { deserializePingPair(benchIters) }
        val after = snapshot()
        val totalPackets = benchIters.toLong() * 2
        val opsPerSec = totalPackets / elapsed.inWholeMilliseconds.toDouble() * 1000

        println("[v5-zero-body] $totalPackets packets in ${elapsed.inWholeMilliseconds}ms = %.0f ops/s".format(opsPerSec))
        println("[v5-zero-body] before: $before")
        println("[v5-zero-body] after:  $after")
        println(
            "[v5-zero-body] delta:  direct=%+d bufs  rss=%+.1fMB".format(
                after.directCount - before.directCount,
                after.rssMB - before.rssMB,
            ),
        )
    }

    private fun readVarInt(stream: StreamProcessor): Int {
        var value = 0
        var multiplier = 1
        var digit: Byte
        do {
            digit = stream.readByte()
            value += (digit.toInt() and 0x7F) * multiplier
            multiplier *= 128
        } while ((digit.toInt() and 0x80) != 0)
        return value
    }
}
