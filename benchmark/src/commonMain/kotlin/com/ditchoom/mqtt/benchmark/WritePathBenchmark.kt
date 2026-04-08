package com.ditchoom.mqtt.benchmark

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.mqtt.client.toBuffer
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.TopicFilter
import com.ditchoom.mqtt.controlpacket.TopicName
import com.ditchoom.mqtt3.controlpacket.PingRequest
import com.ditchoom.mqtt3.controlpacket.PublishAcknowledgment
import com.ditchoom.mqtt3.controlpacket.PublishMessage
import com.ditchoom.mqtt3.controlpacket.SubscribeRequest
import com.ditchoom.mqtt3.controlpacket.Subscription
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.BenchmarkTimeUnit
import kotlinx.benchmark.Blackhole
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
class WritePathBenchmark {
    private lateinit var publishSmall: PublishMessage
    private lateinit var publishLarge: PublishMessage
    private lateinit var subscribe: SubscribeRequest
    private val topic = TopicName.fromOrThrow("bench/topic/foo")

    @Setup
    fun setup() {
        val smallPayload = BufferFactory.Default.allocate(64)
        repeat(64) { smallPayload.writeByte(it.toByte()) }
        smallPayload.resetForRead()
        publishSmall = PublishMessage.buildPayload(
            topicName = topic,
            qos = QualityOfService.AT_LEAST_ONCE,
            packetIdentifier = 1,
            payload = smallPayload,
        )

        val largePayload = BufferFactory.Default.allocate(4096)
        repeat(4096) { largePayload.writeByte(it.toByte()) }
        largePayload.resetForRead()
        publishLarge = PublishMessage.buildPayload(
            topicName = topic,
            qos = QualityOfService.AT_LEAST_ONCE,
            packetIdentifier = 2,
            payload = largePayload,
        )

        val filter = TopicFilter.fromOrThrow("bench/topic/foo")
        subscribe = SubscribeRequest(
            packetIdentifier = 1,
            subscriptions = setOf(Subscription(filter, QualityOfService.AT_LEAST_ONCE)),
        )
    }

    @Benchmark
    fun serializePuback(bh: Blackhole) {
        val puback = PublishAcknowledgment(1u.toUShort())
        val buf = puback.toBuffer()
        bh.consume(buf)
    }

    @Benchmark
    fun serializePingreq(bh: Blackhole) {
        val buf = PingRequest.toBuffer()
        bh.consume(buf)
    }

    @Benchmark
    fun serializePublishSmall(bh: Blackhole) {
        val buf = publishSmall.toBuffer()
        bh.consume(buf)
    }

    @Benchmark
    fun serializePublishLarge(bh: Blackhole) {
        val buf = publishLarge.toBuffer()
        bh.consume(buf)
    }

    @Benchmark
    fun serializeSubscribe(bh: Blackhole) {
        val buf = subscribe.toBuffer()
        bh.consume(buf)
    }
}
