package com.ditchoom.mqtt.benchmark

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.stream.builder
import com.ditchoom.mqtt.controlpacket.TopicName
import com.ditchoom.mqtt3.controlpacket.ControlPacketV4
import com.ditchoom.mqtt3.controlpacket.PublishMessageV4
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.BenchmarkTimeUnit
import kotlinx.benchmark.Blackhole
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.coroutines.channels.Channel

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
class MessageFlowBenchmark {
    private lateinit var publishSmall: PublishMessageV4
    private lateinit var publishSmallBytes: ReadBuffer

    private val topic = TopicName.fromOrThrow("test/flow")

    @Setup
    fun setup() {
        val payload = BufferFactory.Default.allocate(64)
        repeat(64) { payload.writeByte(it.toByte()) }
        payload.resetForRead()
        publishSmall = PublishMessageV4.ofRaw(topic = topic, payload = payload)
        publishSmallBytes = publishSmall.serialize()
    }

    /**
     * Channel trySend → stream append → stream readBuffer.
     * Simulates the WebSocket→StreamProcessor path for MQTT messages.
     */
    @Benchmark
    fun channelToStreamProcessor(bh: Blackhole) {
        val pool = BufferPool()
        val stream = StreamProcessor.builder(pool).build()

        val channel = Channel<ReadBuffer>(Channel.UNLIMITED)

        // Simulate: WebSocket delivers a binary message
        val copy = BufferFactory.Default.allocate(publishSmallBytes.remaining())
        publishSmallBytes.position(0)
        copy.write(publishSmallBytes)
        copy.resetForRead()
        publishSmallBytes.position(0)

        channel.trySend(copy)
        val msg = channel.tryReceive().getOrThrow()

        // Simulate: append to stream and read back
        stream.append(msg)
        val byte1 = stream.readUnsignedByte()
        bh.consume(byte1)
        stream.release()
    }

    /**
     * Full round-trip: serialize PUBLISH → deserialize → verify.
     */
    @Benchmark
    fun fullPacketRoundTrip(bh: Blackhole) {
        val buf = BufferFactory.Default.allocate(publishSmall.packetSize())
        publishSmall.serialize(buf)
        buf.resetForRead()
        val parsed = ControlPacketV4.from(buf)
        bh.consume(parsed)
    }
}
