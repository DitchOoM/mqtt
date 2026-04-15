package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.mqtt.MalformedPacketException
import com.ditchoom.mqtt.MqttException
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.readVariableByteInteger
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.TopicName
import com.ditchoom.mqtt.controlpacket.format.fixed.get
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.fail

class PublishMessageTests {
    private fun freshPayload(): PlatformBuffer {
        val p = BufferFactory.Default.allocate(4)
        p.writeString("yolo", Charset.UTF8)
        p.resetForRead()
        return p
    }

    @Test
    fun qosBothBitsSetTo1ThrowsMalformedPacketException() {
        val byte1 = 0b00111110.toByte()
        val remainingLength = 1.toByte()
        val buffer = BufferFactory.Default.allocate(2)
        buffer.writeByte(byte1)
        buffer.writeByte(remainingLength)
        buffer.resetForRead()
        try {
            ControlPacketV4.from(buffer)
            fail()
        } catch (_: MalformedPacketException) {
        }
    }

    @Test
    fun qos0AndPacketIdentifierThrowsIllegalArgumentException() {
        assertFailsWith(MqttException::class) {
            PublishMessageV4
                .ofRaw(
                    topic = TopicName.fromOrThrow("t"),
                    qos = QualityOfService.AT_MOST_ONCE,
                    packetIdentifier = 2,
                ).validateOrThrow()
        }
    }

    @Test
    fun qos1WithoutPacketIdentifierThrowsIllegalArgumentException() {
        assertFailsWith(MqttException::class) {
            PublishMessageV4
                .ofRaw(
                    topic = TopicName.fromOrThrow("t"),
                    qos = QualityOfService.AT_LEAST_ONCE,
                ).validateOrThrow()
        }
    }

    @Test
    fun qos2WithoutPacketIdentifierThrowsIllegalArgumentException() {
        assertFailsWith(MqttException::class) {
            PublishMessageV4
                .ofRaw(
                    topic = TopicName.fromOrThrow("t"),
                    qos = QualityOfService.EXACTLY_ONCE,
                ).validateOrThrow()
        }
    }

    @Test
    fun genericSerialization() = roundtrip("user/log", expectedRemainingLength = 14)

    @Test
    fun genericSerializationPublishDupFlag() = roundtrip("user/log", dup = true, expectedRemainingLength = 14)

    @Test
    fun genericSerializationPublishQos1() =
        roundtrip(
            "user/log",
            qos = QualityOfService.AT_LEAST_ONCE,
            packetId = 13,
            expectedRemainingLength = 16,
        )

    @Test
    fun genericSerializationPublishQos2() =
        roundtrip(
            "user/log",
            qos = QualityOfService.EXACTLY_ONCE,
            packetId = 13,
            expectedRemainingLength = 16,
        )

    @Test
    fun genericSerializationPublishRetainFlag() = roundtrip("user/log", retain = true, expectedRemainingLength = 14)

    @Test
    fun nullGenericSerialization() =
        runTest {
            val publishMessage = PublishMessageV4.ofRaw(topic = TopicName.fromOrThrow("user/log"))
            val buffer = BufferFactory.Default.allocate(12)
            publishMessage.serialize(buffer)
            buffer.resetForRead()
            val firstByte = buffer.readUnsignedByte()
            assertEquals(3, firstByte.toInt().shr(4), "fixed header control packet type")
            assertFalse(firstByte.get(3), "publish dup flag")
            assertFalse(firstByte.get(2), "qos bit 2")
            assertFalse(firstByte.get(1), "qos bit 1")
            assertFalse(firstByte.get(0), "retain flag")
            assertEquals(10, buffer.readVariableByteInteger(), "remaining length")
            assertEquals(8u, buffer.readUnsignedShort(), "topic name length")
            assertEquals("user/log", buffer.readString(8, Charset.UTF8), "topic name value")
            buffer.resetForRead()
            val byte1 = buffer.readUnsignedByte()
            val remainingLength = buffer.readVariableByteInteger()
            val result = PublishMessageV4.from(buffer, byte1, remainingLength)
            assertMessageIsSame(publishMessage, result)
        }

    private fun roundtrip(
        topic: String,
        qos: QualityOfService = QualityOfService.AT_MOST_ONCE,
        packetId: Int = com.ditchoom.mqtt.controlpacket.NO_PACKET_ID,
        dup: Boolean = false,
        retain: Boolean = false,
        expectedRemainingLength: Int,
    ) = runTest {
        val payload = freshPayload()
        val publishMessage =
            PublishMessageV4.ofRaw(
                topic = TopicName.fromOrThrow(topic),
                qos = qos,
                payload = payload,
                dup = dup,
                retain = retain,
                packetIdentifier = packetId,
            )
        val buffer = BufferFactory.Default.allocate(expectedRemainingLength + 4)
        publishMessage.serialize(buffer)
        buffer.resetForRead()

        val firstByte = buffer.readUnsignedByte()
        assertEquals(3, firstByte.toInt().shr(4), "control packet type")
        assertEquals(dup, firstByte.get(3), "dup flag")
        assertEquals(qos == QualityOfService.EXACTLY_ONCE, firstByte.get(2), "qos bit 2")
        assertEquals(qos == QualityOfService.AT_LEAST_ONCE, firstByte.get(1), "qos bit 1")
        assertEquals(retain, firstByte.get(0), "retain flag")
        assertEquals(expectedRemainingLength, buffer.readVariableByteInteger(), "remaining length")
        assertEquals(topic.length.toUInt().toUShort(), buffer.readUnsignedShort(), "topic length")
        assertEquals(topic, buffer.readString(topic.length, Charset.UTF8), "topic value")
        if (qos != QualityOfService.AT_MOST_ONCE) {
            assertEquals(packetId, buffer.readUnsignedShort().toInt(), "packet identifier")
        }
        assertEquals("yolo", buffer.readString(4, Charset.UTF8), "payload value")
        buffer.resetForRead()
        val byte1 = buffer.readUnsignedByte()
        val remainingLength = buffer.readVariableByteInteger()
        val result = PublishMessageV4.from(buffer, byte1, remainingLength)
        assertEquals(topic, result.topic.toString())
        assertEquals(qos, result.qualityOfService)
        assertEquals(dup, result.dup)
        assertEquals(retain, result.retain)
        if (qos != QualityOfService.AT_MOST_ONCE) assertEquals(packetId, result.packetIdentifier)
        val bytes = result.payload.readByteArray(result.payload.remaining())
        assertContentEquals("yolo".encodeToByteArray(), bytes)
    }

    private fun assertMessageIsSame(
        left: ControlPacketV4,
        right: ControlPacketV4,
    ) {
        val leftSize = left.packetSize()
        val leftBuffer = BufferFactory.Default.allocate(leftSize)
        left.serialize(leftBuffer)
        leftBuffer.resetForRead()

        val rightSize = right.packetSize()
        val rightBuffer = BufferFactory.Default.allocate(rightSize)
        right.serialize(rightBuffer)
        rightBuffer.resetForRead()

        val leftByteArray = leftBuffer.readByteArray(leftSize)
        val rightByteArray = rightBuffer.readByteArray(rightSize)
        assertContentEquals(leftByteArray, rightByteArray)
    }
}
