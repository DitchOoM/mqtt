package com.ditchoom.mqtt.benchmark

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.freeIfNeeded
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.withPooling
import com.ditchoom.mqtt.client.toBuffer
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.TopicName
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
import kotlinx.benchmark.TearDown

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
class PoolBenchmark {
    private lateinit var publish: PublishMessageV4<ReadBuffer>
    private lateinit var pool: BufferPool
    private lateinit var pooledFactory: BufferFactory
    private val topic = TopicName.fromOrThrow("bench/pool/test")

    @Setup
    fun setup() {
        val payload = BufferFactory.Default.allocate(64)
        repeat(64) { payload.writeByte(it.toByte()) }
        payload.resetForRead()
        publish = PublishMessageV4.ofRaw(
            topic = topic,
            qos = QualityOfService.AT_LEAST_ONCE,
            packetIdentifier = 1,
            payload = payload,
        )
        pool = BufferPool()
        pooledFactory = BufferFactory.Default.withPooling(pool)
    }

    @TearDown
    fun teardown() {
        pool.clear()
    }

    @Benchmark
    fun pooledAcquireRelease(bh: Blackhole) {
        val buf = pool.acquire(64)
        buf.writeByte(0x42)
        val cap = buf.capacity
        pool.release(buf)
        bh.consume(cap)
    }

    @Benchmark
    fun pooledSerialize(bh: Blackhole) {
        val buf = publish.toBuffer(pooledFactory)
        val pos = buf.position()
        buf.freeIfNeeded()
        bh.consume(pos)
    }

    @Benchmark
    fun unpooledSerialize(bh: Blackhole) {
        val buf = publish.toBuffer(BufferFactory.Default)
        bh.consume(buf)
    }
}
