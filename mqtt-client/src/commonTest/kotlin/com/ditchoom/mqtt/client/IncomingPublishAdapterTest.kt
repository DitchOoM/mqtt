package com.ditchoom.mqtt.client

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.mqtt.controlpacket.IncomingPublish
import com.ditchoom.mqtt.controlpacket.IncomingPublishV5
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.TopicName
import com.ditchoom.mqtt3.controlpacket.PublishMessage as V4PublishMessage
import com.ditchoom.mqtt5.controlpacket.PublishMessage as V5PublishMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class IncomingPublishAdapterTest {

    private fun makePayload(vararg bytes: Byte): ReadBuffer {
        val buf = BufferFactory.Default.allocate(bytes.size)
        bytes.forEach { buf.writeByte(it) }
        buf.resetForRead()
        return buf
    }

    // ── toIncomingPublish: V4 ────────────────────────────────────────────

    @Test
    fun v4PublishAdaptsCorrectly() {
        val payload = makePayload(1, 2, 3)
        val wire = V4PublishMessage(
            topicName = "sensor/temp",
            qos = QualityOfService.AT_LEAST_ONCE,
            dup = true,
            retain = false,
            packetIdentifier = 42,
            payload = payload,
        )
        val incoming = wire.toIncomingPublish()
        assertEquals(TopicName.fromOrThrow("sensor/temp"), incoming.topic)
        assertEquals(QualityOfService.AT_LEAST_ONCE, incoming.qos)
        assertEquals(true, incoming.dup)
        assertEquals(false, incoming.retain)
        assertEquals(3, incoming.payload?.remaining())
    }

    @Test
    fun v4NullPayloadAdaptsToNull() {
        val wire = V4PublishMessage(
            topicName = "test",
            qos = QualityOfService.AT_MOST_ONCE,
            payload = null,
        )
        val incoming = wire.toIncomingPublish()
        assertNull(incoming.payload)
    }

    // ── toIncomingPublish: V5 with properties ────────────────────────────

    @Test
    fun v5PublishAdaptsWithProperties() {
        val payload = makePayload(0xCA.toByte(), 0xFE.toByte())
        val wire = V5PublishMessage(
            topicName = "chat/room1",
            qos = QualityOfService.EXACTLY_ONCE,
            dup = false,
            retain = true,
            packetIdentifier = 100,
            payloadFormatIndicator = true,
            messageExpiryInterval = 3600,
            contentType = "application/json",
            responseTopicName = "reply/room1",
            payload = payload,
        )
        val incoming = wire.toIncomingPublish()
        assertIs<IncomingPublishV5<ReadBuffer?>>(incoming)
        assertEquals(TopicName.fromOrThrow("chat/room1"), incoming.topic)
        assertEquals(QualityOfService.EXACTLY_ONCE, incoming.qos)
        assertEquals(true, incoming.retain)
        assertEquals(true, incoming.payloadFormatIndicator)
        assertEquals(3600L, incoming.messageExpiryInterval)
        assertEquals("application/json", incoming.contentType)
        assertEquals(TopicName.fromOrThrow("reply/room1"), incoming.responseTopic)
        assertEquals(true, incoming.isRequest)
    }

    // ── withDecodedPayload preserves metadata ────────────────────────────

    @Test
    fun withDecodedPayloadPreservesV4Metadata() {
        val wire = V4PublishMessage(
            topicName = "test/topic",
            qos = QualityOfService.AT_LEAST_ONCE,
            dup = true,
            retain = true,
            packetIdentifier = 7,
            payload = makePayload(0x00, 0x2A), // Short 42
        )
        val raw: IncomingPublish<ReadBuffer?> = wire.toIncomingPublish()
        val decoded: IncomingPublish<Int> = raw.withDecodedPayload(42)

        assertEquals(42, decoded.payload)
        assertEquals(TopicName.fromOrThrow("test/topic"), decoded.topic)
        assertEquals(QualityOfService.AT_LEAST_ONCE, decoded.qos)
        assertEquals(true, decoded.dup)
        assertEquals(true, decoded.retain)
    }

    @Test
    fun withDecodedPayloadPreservesV5SmartCast() {
        val wire = V5PublishMessage(
            topicName = "v5/topic",
            contentType = "text/plain",
            responseTopicName = "reply/here",
            payload = makePayload(1),
        )
        val raw: IncomingPublish<ReadBuffer?> = wire.toIncomingPublish()
        val decoded: IncomingPublish<String> = raw.withDecodedPayload("hello")

        assertIs<IncomingPublishV5<String>>(decoded)
        assertEquals("hello", decoded.payload)
        assertEquals("text/plain", decoded.contentType)
        assertEquals(TopicName.fromOrThrow("reply/here"), decoded.responseTopic)
    }

    // ── ScopedIncomingPublish ────────────────────────────────────────────

    @Test
    fun scopedIncomingPublishDelegatesMetadata() {
        val wire = V4PublishMessage(
            topicName = "scoped/test",
            qos = QualityOfService.AT_MOST_ONCE,
            payload = makePayload(1, 2),
        )
        val raw = wire.toIncomingPublish()
        val scoped = ScopedIncomingPublish(raw, ScopedReadBuffer(raw.payload!!))

        assertEquals(TopicName.fromOrThrow("scoped/test"), scoped.topic)
        assertEquals(QualityOfService.AT_MOST_ONCE, scoped.qos)
        // Payload is the scoped wrapper, not the original
        assertEquals(2, scoped.payload?.remaining())
    }
}
