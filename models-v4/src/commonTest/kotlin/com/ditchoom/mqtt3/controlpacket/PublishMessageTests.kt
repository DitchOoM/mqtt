package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.mqtt.MalformedPacketException
import com.ditchoom.mqtt.MqttException
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.readVariableByteInteger
import com.ditchoom.mqtt.controlpacket.MqttFixedHeader
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.format.fixed.get
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.fail

class PublishMessageTests {
    private fun publishMessage(
        topic: String,
        qos: QualityOfService = QualityOfService.AT_MOST_ONCE,
        packetId: Int = com.ditchoom.mqtt.controlpacket.NO_PACKET_ID,
        dup: Boolean = false,
        retain: Boolean = false,
        payloadString: String = "",
    ): PublishMessageV4<NonSpecCompliantIntermediaryStringAsBuffer> =
        PublishMessageV4(
            header = MqttFixedHeader(makePublishHeaderByteV4(dup, qos, retain)),
            topicName = topic,
            packetId = if (packetId == com.ditchoom.mqtt.controlpacket.NO_PACKET_ID) null else packetId.toUShort(),
            payload = NonSpecCompliantIntermediaryStringAsBuffer(payloadString),
        )

    @Test
    fun qosBothBitsSetTo1ThrowsMalformedPacketException() {
        val byte1 = 0b00111110.toByte()
        val remainingLength = 1.toByte()
        val buffer = BufferFactory.Default.allocate(2)
        buffer.writeByte(byte1)
        buffer.writeByte(remainingLength)
        buffer.resetForRead()
        try {
            decodeV4(buffer)
            fail()
        } catch (_: MalformedPacketException) {
        }
    }

    @Test
    fun qos0AndPacketIdentifierThrowsIllegalArgumentException() {
        assertFailsWith(MqttException::class) {
            publishMessage(
                topic = "t",
                qos = QualityOfService.AT_MOST_ONCE,
                packetId = 2,
            ).validateOrThrow()
        }
    }

    @Test
    fun qos1WithoutPacketIdentifierThrowsIllegalArgumentException() {
        assertFailsWith(MqttException::class) {
            publishMessage(
                topic = "t",
                qos = QualityOfService.AT_LEAST_ONCE,
            ).validateOrThrow()
        }
    }

    @Test
    fun qos2WithoutPacketIdentifierThrowsIllegalArgumentException() {
        assertFailsWith(MqttException::class) {
            publishMessage(
                topic = "t",
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
            val pm = publishMessage(topic = "user/log")
            val buffer = BufferFactory.Default.allocate(12)
            serializeV4(pm, buffer)
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
            val result = decodeV4(buffer) as PublishMessageV4<*>
            assertMessageIsSame(pm, result)
        }

    private fun roundtrip(
        topic: String,
        qos: QualityOfService = QualityOfService.AT_MOST_ONCE,
        packetId: Int = com.ditchoom.mqtt.controlpacket.NO_PACKET_ID,
        dup: Boolean = false,
        retain: Boolean = false,
        expectedRemainingLength: Int,
    ) = runTest {
        val pm =
            publishMessage(
                topic = topic,
                qos = qos,
                dup = dup,
                retain = retain,
                packetId = packetId,
                payloadString = "yolo",
            )
        val buffer = BufferFactory.Default.allocate(expectedRemainingLength + 4)
        serializeV4(pm, buffer)
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
        @Suppress("UNCHECKED_CAST")
        val result = decodeV4(buffer) as PublishMessageV4<NonSpecCompliantIntermediaryStringAsBuffer>
        assertEquals(topic, result.topic.toString())
        assertEquals(qos, result.qualityOfService)
        assertEquals(dup, result.dup)
        assertEquals(retain, result.retain)
        if (qos != QualityOfService.AT_MOST_ONCE) assertEquals(packetId, result.packetIdentifier)
        assertContentEquals("yolo".encodeToByteArray(), result.payload.s.encodeToByteArray())
    }

    private fun assertMessageIsSame(
        left: ControlPacketV4<*>,
        right: ControlPacketV4<*>,
    ) {
        val leftSize = packetSizeV4(left)
        val leftBuffer = BufferFactory.Default.allocate(leftSize)
        serializeV4(left, leftBuffer)
        leftBuffer.resetForRead()

        val rightSize = packetSizeV4(right)
        val rightBuffer = BufferFactory.Default.allocate(rightSize)
        serializeV4(right, rightBuffer)
        rightBuffer.resetForRead()

        val leftByteArray = leftBuffer.readByteArray(leftSize)
        val rightByteArray = rightBuffer.readByteArray(rightSize)
        assertContentEquals(leftByteArray, rightByteArray)
    }
}
