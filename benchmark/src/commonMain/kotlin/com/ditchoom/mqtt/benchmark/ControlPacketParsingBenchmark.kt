package com.ditchoom.mqtt.benchmark

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.TopicName
import com.ditchoom.mqtt3.controlpacket.ConnectionAcknowledgment
import com.ditchoom.mqtt3.controlpacket.ControlPacketV4
import com.ditchoom.mqtt3.controlpacket.ControlPacketV4Factory
import com.ditchoom.mqtt3.controlpacket.PingRequest
import com.ditchoom.mqtt3.controlpacket.PublishMessage
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
class ControlPacketParsingBenchmark {
    private lateinit var connackBytes: PlatformBuffer
    private lateinit var pingReqBytes: PlatformBuffer
    private lateinit var publishSmallBytes: PlatformBuffer
    private lateinit var publishMediumBytes: PlatformBuffer
    private lateinit var publishLargeBytes: PlatformBuffer

    private lateinit var publishSmall: PublishMessage
    private lateinit var publishMedium: PublishMessage
    private lateinit var publishLarge: PublishMessage

    private val topic = TopicName.fromOrThrow("test/benchmark")

    @Setup
    fun setup() {
        // CONNACK
        val connack = ConnectionAcknowledgment()
        connackBytes = connack.serialize()
        connackBytes.resetForRead()

        // PINGREQ
        pingReqBytes = PingRequest.serialize()
        pingReqBytes.resetForRead()

        // PUBLISH with 64-byte payload
        val smallPayload = BufferFactory.Default.allocate(64)
        repeat(64) { smallPayload.writeByte(it.toByte()) }
        smallPayload.resetForRead()
        publishSmall = PublishMessage.buildPayload(topicName = topic, payload = smallPayload)
        publishSmallBytes = publishSmall.serialize()
        publishSmallBytes.resetForRead()

        // PUBLISH with 1KB payload
        val mediumPayload = BufferFactory.Default.allocate(1024)
        repeat(1024) { mediumPayload.writeByte(it.toByte()) }
        mediumPayload.resetForRead()
        publishMedium = PublishMessage.buildPayload(topicName = topic, payload = mediumPayload)
        publishMediumBytes = publishMedium.serialize()
        publishMediumBytes.resetForRead()

        // PUBLISH with 64KB payload
        val largePayload = BufferFactory.Default.allocate(65536)
        repeat(65536) { largePayload.writeByte(it.toByte()) }
        largePayload.resetForRead()
        publishLarge = PublishMessage.buildPayload(topicName = topic, payload = largePayload)
        publishLargeBytes = publishLarge.serialize()
        publishLargeBytes.resetForRead()
    }

    @Benchmark
    fun parseConnack(bh: Blackhole) {
        connackBytes.position(0)
        val packet = ControlPacketV4.from(connackBytes)
        bh.consume(packet)
    }

    @Benchmark
    fun parsePingReq(bh: Blackhole) {
        pingReqBytes.position(0)
        val packet = ControlPacketV4.from(pingReqBytes)
        bh.consume(packet)
    }

    @Benchmark
    fun parsePublishSmall(bh: Blackhole) {
        publishSmallBytes.position(0)
        val packet = ControlPacketV4.from(publishSmallBytes)
        bh.consume(packet)
    }

    @Benchmark
    fun parsePublishMedium(bh: Blackhole) {
        publishMediumBytes.position(0)
        val packet = ControlPacketV4.from(publishMediumBytes)
        bh.consume(packet)
    }

    @Benchmark
    fun parsePublishLarge(bh: Blackhole) {
        publishLargeBytes.position(0)
        val packet = ControlPacketV4.from(publishLargeBytes)
        bh.consume(packet)
    }

    @Benchmark
    fun serializePublishSmall(bh: Blackhole) {
        val buf = BufferFactory.Default.allocate(publishSmall.packetSize())
        publishSmall.serialize(buf)
        bh.consume(buf)
    }

    @Benchmark
    fun serializePublishMedium(bh: Blackhole) {
        val buf = BufferFactory.Default.allocate(publishMedium.packetSize())
        publishMedium.serialize(buf)
        bh.consume(buf)
    }

    @Benchmark
    fun serializePublishLarge(bh: Blackhole) {
        val buf = BufferFactory.Default.allocate(publishLarge.packetSize())
        publishLarge.serialize(buf)
        bh.consume(buf)
    }
}
