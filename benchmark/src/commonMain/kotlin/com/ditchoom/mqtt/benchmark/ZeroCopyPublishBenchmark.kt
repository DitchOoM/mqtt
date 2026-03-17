package com.ditchoom.mqtt.benchmark

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.mqtt.client.serializeHeaderToSlice
import com.ditchoom.mqtt.client.toBuffer
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.TopicName
import com.ditchoom.mqtt3.controlpacket.PublishMessage
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.BenchmarkTimeUnit
import kotlinx.benchmark.Blackhole
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
class ZeroCopyPublishBenchmark {
    @Param("64", "1024", "4096", "32768")
    var payloadSize: Int = 64

    private lateinit var publish: PublishMessage
    private lateinit var payload: PlatformBuffer
    private val topic = TopicName.fromOrThrow("bench/zero-copy/test")
    private val topicStr = "bench/zero-copy/test"

    @Setup
    fun setup() {
        payload = BufferFactory.Default.allocate(payloadSize)
        repeat(payloadSize) { payload.writeByte(it.toByte()) }
        payload.resetForRead()
        publish = PublishMessage.buildPayload(
            topicName = topic,
            qos = QualityOfService.AT_LEAST_ONCE,
            packetIdentifier = 1,
            payload = payload,
        )
    }

    /** Full serialize to a single buffer (copies payload). */
    @Benchmark
    fun publishToBuffer(bh: Blackhole) {
        val buf = publish.toBuffer()
        bh.consume(buf)
    }

    /** Header-only serialize + buffer list creation (zero-copy path). */
    @Benchmark
    fun publishZeroCopyHeader(bh: Blackhole) {
        val headerBuf = BufferFactory.Default.allocate(128)
        val header = publish.serializeHeaderToSlice(headerBuf, payload.remaining())
        val gathered: List<ReadBuffer> = listOf(header, payload)
        bh.consume(gathered)
    }
}
