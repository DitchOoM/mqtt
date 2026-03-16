package com.ditchoom.mqtt.client

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.utf8Length
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.TopicName
import com.ditchoom.mqtt3.controlpacket.PublishMessage
import com.ditchoom.mqtt5.controlpacket.PublishMessage as PublishMessageV5
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [serializeHeaderToSlice] correctness across topic sizes,
 * ensuring the header buffer is large enough and round-trip works.
 */
class HeaderSerializationTest {

    private fun buildPublish(
        topicStr: String,
        qos: QualityOfService = QualityOfService.AT_LEAST_ONCE,
        payloadSize: Int = 64,
        packetId: Int = 1,
    ): Pair<PublishMessage, PlatformBuffer> {
        val topic = TopicName.fromOrThrow(topicStr)
        val payload = BufferFactory.Default.allocate(payloadSize)
        repeat(payloadSize) { payload.writeByte((it % 256).toByte()) }
        payload.resetForRead()
        return PublishMessage.buildPayload(
            topicName = topic,
            qos = qos,
            packetIdentifier = packetId,
            payload = payload,
        ) to payload
    }

    private fun headerBufferSize(topicStr: String): Int {
        val topicBudget = minOf(topicStr.length * 4, 65_535)
        return 9 + topicBudget
    }

    @Test
    fun shortAsciiTopic() {
        val topicStr = "t"
        val (pub, payload) = buildPublish(topicStr)
        val bufSize = headerBufferSize(topicStr)
        val buf = BufferFactory.Default.allocate(bufSize)
        val header = pub.serializeHeaderToSlice(buf, payload.remaining())
        // Header must fit: 5 (fixed) + 2 (topic len) + 1 (topic) + 2 (packet ID) = 10 bytes max
        assertTrue(header.remaining() <= 10, "Short topic header too large: ${header.remaining()}")
        assertTrue(header.remaining() >= 7, "Short topic header too small: ${header.remaining()}")
    }

    @Test
    fun mediumAsciiTopic() {
        val topicStr = "bench/topic/foo"
        val (pub, payload) = buildPublish(topicStr)
        val bufSize = headerBufferSize(topicStr)
        val buf = BufferFactory.Default.allocate(bufSize)
        val header = pub.serializeHeaderToSlice(buf, payload.remaining())
        // 5 + 2 + 15 + 2 = 24 max
        assertTrue(header.remaining() in 20..24, "Medium topic header unexpected size: ${header.remaining()}")
    }

    @Test
    fun longAsciiTopic() {
        val topicStr = "a".repeat(200)
        val (pub, payload) = buildPublish(topicStr)
        val bufSize = headerBufferSize(topicStr)
        val buf = BufferFactory.Default.allocate(bufSize)
        val header = pub.serializeHeaderToSlice(buf, payload.remaining())
        // 5 + 2 + 200 + 2 = 209 max
        assertTrue(header.remaining() in 205..209, "Long topic header unexpected size: ${header.remaining()}")
    }

    @Test
    fun nonAsciiCjkTopic() {
        // CJK characters: each is 3 bytes in UTF-8
        val topicStr = "test/\u6570\u636E" // "test/数据"
        val expectedUtf8Len = topicStr.utf8Length()
        assertEquals(11, expectedUtf8Len, "CJK topic UTF-8 length should be 11")

        val (pub, payload) = buildPublish(topicStr)
        val bufSize = headerBufferSize(topicStr)
        val buf = BufferFactory.Default.allocate(bufSize)
        val header = pub.serializeHeaderToSlice(buf, payload.remaining())
        // 5 + 2 + 11 + 2 = 20 max
        assertTrue(header.remaining() in 16..20, "CJK topic header unexpected size: ${header.remaining()}")
    }

    @Test
    fun maxLengthTopic() {
        // Max MQTT topic: 65,535 UTF-8 bytes (all ASCII for simplicity)
        val topicStr = "a".repeat(65_535)
        val (pub, payload) = buildPublish(topicStr, payloadSize = 0, qos = QualityOfService.AT_MOST_ONCE, packetId = 0)
        val bufSize = headerBufferSize(topicStr)
        assertTrue(bufSize >= 65_535 + 7, "Buffer should be large enough for max topic")
        val buf = BufferFactory.Default.allocate(bufSize)
        val header = pub.serializeHeaderToSlice(buf, payload.remaining())
        // Header must contain the full topic
        assertTrue(header.remaining() >= 65_535 + 4, "Max topic header too small: ${header.remaining()}")
    }

    @Test
    fun qos0NoPacketId() {
        val topicStr = "test/qos0"
        val topic = TopicName.fromOrThrow(topicStr)
        val payload = BufferFactory.Default.allocate(10)
        repeat(10) { payload.writeByte(it.toByte()) }
        payload.resetForRead()
        val pub = PublishMessage.buildPayload(
            topicName = topic,
            qos = QualityOfService.AT_MOST_ONCE,
            payload = payload,
        )
        val bufSize = headerBufferSize(topicStr)
        val buf = BufferFactory.Default.allocate(bufSize)
        val header = pub.serializeHeaderToSlice(buf, payload.remaining())
        // QoS 0 has no packet ID: 5 + 2 + 9 = 16 max (no +2 for packet ID)
        assertTrue(header.remaining() in 12..16, "QoS0 header unexpected size: ${header.remaining()}")
    }

    /**
     * Verify that serializeHeaderToSlice + payload produces identical bytes to full serialize.
     */
    @Test
    fun roundTripSerializeDeserialize() {
        val topicStr = "sensor/temperature/living-room"
        val payloadBytes = ByteArray(128) { (it % 256).toByte() }
        val topic = TopicName.fromOrThrow(topicStr)
        val payload = PlatformBuffer.wrap(payloadBytes)
        val pub = PublishMessage.buildPayload(
            topicName = topic,
            qos = QualityOfService.AT_LEAST_ONCE,
            packetIdentifier = 42,
            payload = payload,
        )

        // Full serialize (reference)
        payload.resetForRead()
        val fullBuf = pub.toBuffer()
        fullBuf.resetForRead()
        val expectedBytes = fullBuf.readByteArray(fullBuf.remaining())

        // Zero-copy path: header slice + payload
        payload.resetForRead()
        val bufSize = headerBufferSize(topicStr)
        val headerBuf = BufferFactory.Default.allocate(bufSize)
        val header = pub.serializeHeaderToSlice(headerBuf, payload.remaining())

        val combined = BufferFactory.Default.allocate(header.remaining() + payload.remaining())
        combined.write(header)
        combined.write(payload)
        combined.resetForRead()
        val actualBytes = combined.readByteArray(combined.remaining())

        assertEquals(expectedBytes.size, actualBytes.size, "Serialized size mismatch")
        assertTrue(expectedBytes.contentEquals(actualBytes), "Serialized bytes mismatch")
    }

    /**
     * Regression: v5 PUBLISH zero-copy path was missing properties serialization.
     * serializeHeaderToSlice wrote topic + packetId but skipped properties,
     * causing brokers to reject the packet as MALFORMED_PACKET.
     */
    @Test
    fun roundTripV5PublishWithEmptyProperties() {
        val topicStr = "sensor/temperature"
        val payloadBytes = ByteArray(64) { (it % 256).toByte() }
        val payload = PlatformBuffer.wrap(payloadBytes)
        val pub = PublishMessageV5(
            topicName = topicStr,
            qos = com.ditchoom.mqtt.controlpacket.QualityOfService.AT_LEAST_ONCE,
            packetIdentifier = 10,
            payload = payload,
        )

        // Full serialize (reference)
        payload.resetForRead()
        val fullBuf = pub.toBuffer()
        fullBuf.resetForRead()
        val expectedBytes = fullBuf.readByteArray(fullBuf.remaining())

        // Zero-copy path: header slice + payload
        payload.resetForRead()
        val cp = pub as com.ditchoom.mqtt.controlpacket.ControlPacket
        val headerSize = cp.packetSize() - payload.remaining()
        val headerBuf = BufferFactory.Default.allocate(headerSize + com.ditchoom.mqtt.controlpacket.ControlPacket.MAX_FIXED_HEADER_SIZE)
        val header = pub.serializeHeaderToSlice(headerBuf, payload.remaining())

        val combined = BufferFactory.Default.allocate(header.remaining() + payload.remaining())
        combined.write(header)
        combined.write(payload)
        combined.resetForRead()
        val actualBytes = combined.readByteArray(combined.remaining())

        assertEquals(expectedBytes.size, actualBytes.size, "v5 serialized size mismatch")
        assertTrue(expectedBytes.contentEquals(actualBytes), "v5 serialized bytes mismatch")
    }

    /**
     * Regression: v5 PUBLISH with user properties must include those properties in
     * the zero-copy header. This is the exact scenario that triggered MALFORMED_PACKET.
     */
    @Test
    fun roundTripV5PublishWithUserProperties() {
        val topicStr = "device/status"
        val payloadBytes = ByteArray(32) { it.toByte() }
        val payload = PlatformBuffer.wrap(payloadBytes)
        val pub = PublishMessageV5(
            topicName = topicStr,
            qos = com.ditchoom.mqtt.controlpacket.QualityOfService.EXACTLY_ONCE,
            packetIdentifier = 42,
            userProperty = listOf("key1" to "val1", "key2" to "val2"),
            contentType = "application/json",
            payload = payload,
        )

        // Full serialize (reference)
        payload.resetForRead()
        val fullBuf = pub.toBuffer()
        fullBuf.resetForRead()
        val expectedBytes = fullBuf.readByteArray(fullBuf.remaining())

        // Zero-copy path
        payload.resetForRead()
        val cp = pub as com.ditchoom.mqtt.controlpacket.ControlPacket
        val headerSize = cp.packetSize() - payload.remaining()
        val headerBuf = BufferFactory.Default.allocate(headerSize + com.ditchoom.mqtt.controlpacket.ControlPacket.MAX_FIXED_HEADER_SIZE)
        val header = pub.serializeHeaderToSlice(headerBuf, payload.remaining())

        val combined = BufferFactory.Default.allocate(header.remaining() + payload.remaining())
        combined.write(header)
        combined.write(payload)
        combined.resetForRead()
        val actualBytes = combined.readByteArray(combined.remaining())

        assertEquals(expectedBytes.size, actualBytes.size, "v5 with properties serialized size mismatch")
        assertTrue(expectedBytes.contentEquals(actualBytes), "v5 with properties serialized bytes mismatch")
    }

    @Test
    fun roundTripLongTopic() {
        val topicStr = "device/" + "x".repeat(500) + "/status"
        val payloadBytes = ByteArray(32) { it.toByte() }
        val topic = TopicName.fromOrThrow(topicStr)
        val payload = PlatformBuffer.wrap(payloadBytes)
        val pub = PublishMessage.buildPayload(
            topicName = topic,
            qos = QualityOfService.AT_LEAST_ONCE,
            packetIdentifier = 99,
            payload = payload,
        )

        // Full serialize (reference)
        payload.resetForRead()
        val fullBuf = pub.toBuffer()
        fullBuf.resetForRead()
        val expectedBytes = fullBuf.readByteArray(fullBuf.remaining())

        // Zero-copy path
        payload.resetForRead()
        val bufSize = headerBufferSize(topicStr)
        val headerBuf = BufferFactory.Default.allocate(bufSize)
        val header = pub.serializeHeaderToSlice(headerBuf, payload.remaining())

        val combined = BufferFactory.Default.allocate(header.remaining() + payload.remaining())
        combined.write(header)
        combined.write(payload)
        combined.resetForRead()
        val actualBytes = combined.readByteArray(combined.remaining())

        assertEquals(expectedBytes.size, actualBytes.size, "Serialized size mismatch")
        assertTrue(expectedBytes.contentEquals(actualBytes), "Serialized bytes mismatch")
    }
}
