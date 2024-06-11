package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.allocate
import com.ditchoom.mqtt.MalformedPacketException
import com.ditchoom.mqtt.MqttException
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.readVariableByteInteger
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.Topic
import com.ditchoom.mqtt.controlpacket.format.fixed.get
import com.ditchoom.mqtt.controlpacket.validControlPacketIdentifierRange
import com.ditchoom.mqtt3.controlpacket.PublishMessage.FixedHeader
import com.ditchoom.mqtt3.controlpacket.PublishMessage.VariableHeader
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class PublishMessageTests {
    private val payload: PlatformBuffer = PlatformBuffer.allocate(4)

    init {
        payload.writeString("yolo", Charset.UTF8)
        payload.resetForRead()
    }

    @Test
    fun qosBothBitsSetTo1ThrowsMalformedPacketException() {
        val byte1 = 0b00111110.toByte()
        val remainingLength = 1.toByte()
        val buffer = PlatformBuffer.allocate(2)
        buffer.writeByte(byte1)
        buffer.writeByte(remainingLength)
        buffer.resetForRead()
        try {
            ControlPacketV4.from(buffer)
            fail()
        } catch (e: MalformedPacketException) {
        }
    }

    @Test
    fun qos0AndPacketIdentifierThrowsIllegalArgumentException() {
        val fixed = FixedHeader(qos = QualityOfService.AT_MOST_ONCE)
        val variable = VariableHeader(checkNotNull(Topic.fromOrThrow("t", Topic.Type.Name)), 2)
        assertFailsWith(MqttException::class) { PublishMessage(fixed, variable).validateOrThrow() }
    }

    @Test
    fun qos1WithoutPacketIdentifierThrowsIllegalArgumentException() {
        val fixed = FixedHeader(qos = QualityOfService.AT_LEAST_ONCE)
        val variable = VariableHeader(checkNotNull(Topic.fromOrThrow("t", Topic.Type.Name)))
        assertFailsWith(MqttException::class) { PublishMessage(fixed, variable).validateOrThrow() }
    }

    @Test
    fun qos2WithoutPacketIdentifierThrowsIllegalArgumentException() {
        val fixed = FixedHeader(qos = QualityOfService.EXACTLY_ONCE)
        val variable = VariableHeader(checkNotNull(Topic.fromOrThrow("t", Topic.Type.Name)))
        assertFailsWith(MqttException::class) { PublishMessage(fixed, variable).validateOrThrow() }
    }

    @Test
    fun genericSerialization() {
        val publishMessage =
            PublishMessage.buildPayload(
                topicName = checkNotNull(Topic.fromOrThrow("user/log", Topic.Type.Name)),
                payload = payload,
            )
        val buffer = PlatformBuffer.allocate(16)
        publishMessage.serialize(buffer)
        val publishPayload = publishMessage.payload
        publishPayload?.position(0)
        buffer.resetForRead()
        val firstByte = buffer.readUnsignedByte()
        assertEquals(firstByte.toInt().shr(4), 3, "fixed header control packet type")
        assertFalse(firstByte.get(3), "fixed header publish dup flag")
        assertFalse(firstByte.get(2), "fixed header qos bit 2")
        assertFalse(firstByte.get(1), "fixed header qos bit 1")
        assertFalse(firstByte.get(0), "fixed header retain flag")
        assertEquals(buffer.readVariableByteInteger(), 14, "fixed header remaining length")
        assertEquals(8u, buffer.readUnsignedShort(), "variable header topic name length")
        assertEquals(
            checkNotNull(Topic.fromOrThrow("user/log", Topic.Type.Name)).toString(),
            buffer.readString(8, Charset.UTF8),
            "variable header topic name value",
        )
        if (publishMessage.variable.packetIdentifier in validControlPacketIdentifierRange) {
            assertEquals(
                buffer.readUnsignedShort().toInt(),
                publishMessage.variable.packetIdentifier,
            )
        }
        assertEquals("yolo", buffer.readString(4, Charset.UTF8), "payload value")
        buffer.resetForRead()
        val byte1 = buffer.readUnsignedByte()
        val remainingLength = buffer.readVariableByteInteger()
        val result = PublishMessage.from(buffer, byte1, remainingLength)
        assertMessageIsSame(publishMessage, result)
    }

    private fun assertMessageIsSame(
        left: ControlPacketV4,
        right: ControlPacketV4,
    ) {
        val leftSize = left.packetSize()
        val leftBuffer = PlatformBuffer.allocate(leftSize)
        left.serialize(leftBuffer)
        leftBuffer.resetForRead()

        val rightSize = right.packetSize()
        val rightBuffer = PlatformBuffer.allocate(rightSize)
        right.serialize(rightBuffer)
        rightBuffer.resetForRead()

        val leftByteArray = leftBuffer.readByteArray(leftSize)
        val rightByteArray = rightBuffer.readByteArray(rightSize)
        assertContentEquals(leftByteArray, rightByteArray)
    }

    @Test
    fun genericSerializationPublishDupFlag() {
        val publishMessage =
            PublishMessage.buildPayload(
                topicName = checkNotNull(Topic.fromOrThrow("user/log", Topic.Type.Name)),
                payload = payload,
                dup = true,
            )
        val buffer = PlatformBuffer.allocate(16)
        publishMessage.serialize(buffer)
        publishMessage.payload?.position(0)
        buffer.resetForRead()
        val firstByte = buffer.readUnsignedByte()
        assertEquals(firstByte.toInt().shr(4), 3, "fixed header control packet type")
        assertTrue(firstByte.get(3), "fixed header publish dup flag")
        assertFalse(firstByte.get(2), "fixed header qos bit 2")
        assertFalse(firstByte.get(1), "fixed header qos bit 1")
        assertFalse(firstByte.get(0), "fixed header retain flag")
        assertEquals(buffer.readVariableByteInteger(), 14, "fixed header remaining length")
        assertEquals(8u, buffer.readUnsignedShort(), "variable header topic name length")
        assertEquals(
            checkNotNull(Topic.fromOrThrow("user/log", Topic.Type.Name)).toString(),
            buffer.readString(8, Charset.UTF8),
            "variable header topic name value",
        )
        if (publishMessage.variable.packetIdentifier in validControlPacketIdentifierRange) {
            assertEquals(
                buffer.readUnsignedShort().toInt(),
                publishMessage.variable.packetIdentifier,
            )
        }
        assertEquals("yolo", buffer.readString(4, Charset.UTF8), "payload value")
        buffer.resetForRead()
        val byte1 = buffer.readUnsignedByte()
        val remainingLength = buffer.readVariableByteInteger()
        val result = PublishMessage.from(buffer, byte1, remainingLength)
        assertMessageIsSame(publishMessage, result)
    }

    @Test
    fun genericSerializationPublishQos1() {
        val publishMessage =
            PublishMessage.buildPayload(
                topicName = checkNotNull(Topic.fromOrThrow("user/log", Topic.Type.Name)),
                payload = payload,
                qos = QualityOfService.AT_LEAST_ONCE,
                packetIdentifier = 13,
            )
        val buffer = PlatformBuffer.allocate(18)
        publishMessage.serialize(buffer)
        publishMessage.payload?.position(0)
        buffer.resetForRead()
        val firstByte = buffer.readUnsignedByte()
        assertEquals(firstByte.toInt().shr(4), 3, "fixed header control packet type")
        assertFalse(firstByte.get(3), "fixed header publish dup flag")
        assertFalse(firstByte.get(2), "fixed header qos bit 2")
        assertTrue(firstByte.get(1), "fixed header qos bit 1")
        assertFalse(firstByte.get(0), "fixed header retain flag")
        assertEquals(buffer.readVariableByteInteger(), 16, "fixed header remaining length")
        assertEquals(8u, buffer.readUnsignedShort(), "variable header topic name length")
        assertEquals(
            checkNotNull(Topic.fromOrThrow("user/log", Topic.Type.Name)).toString(),
            buffer.readString(8, Charset.UTF8),
            "variable header topic name value",
        )
        if (publishMessage.variable.packetIdentifier in validControlPacketIdentifierRange) {
            assertEquals(
                buffer.readUnsignedShort().toInt(),
                publishMessage.variable.packetIdentifier,
            )
        }
        assertEquals("yolo", buffer.readString(4, Charset.UTF8), "payload value")
        buffer.resetForRead()
        val byte1 = buffer.readUnsignedByte()
        val remainingLength = buffer.readVariableByteInteger()
        val result = PublishMessage.from(buffer, byte1, remainingLength)
        assertMessageIsSame(publishMessage, result)
    }

    @Test
    fun genericSerializationPublishQos2() {
        val publishMessage =
            PublishMessage.buildPayload(
                topicName = checkNotNull(Topic.fromOrThrow("user/log", Topic.Type.Name)),
                payload = payload,
                qos = QualityOfService.EXACTLY_ONCE,
                packetIdentifier = 13,
            )
        val buffer = PlatformBuffer.allocate(18)
        publishMessage.serialize(buffer)
        publishMessage.payload?.position(0)
        buffer.resetForRead()
        val firstByte = buffer.readUnsignedByte()
        assertEquals(firstByte.toInt().shr(4), 3, "fixed header control packet type")
        assertFalse(firstByte.get(3), "fixed header publish dup flag")
        assertTrue(firstByte.get(2), "fixed header qos bit 2")
        assertFalse(firstByte.get(1), "fixed header qos bit 1")
        assertFalse(firstByte.get(0), "fixed header retain flag")
        assertEquals(buffer.readVariableByteInteger(), 16, "fixed header remaining length")
        assertEquals(8u, buffer.readUnsignedShort(), "variable header topic name length")
        assertEquals(
            checkNotNull(Topic.fromOrThrow("user/log", Topic.Type.Name)).toString(),
            buffer.readString(8, Charset.UTF8),
            "variable header topic name value",
        )
        if (publishMessage.variable.packetIdentifier in validControlPacketIdentifierRange) {
            assertEquals(
                buffer.readUnsignedShort().toInt(),
                publishMessage.variable.packetIdentifier,
            )
        }
        assertEquals("yolo", buffer.readString(4, Charset.UTF8), "payload value")
        buffer.resetForRead()
        val byte1 = buffer.readUnsignedByte()
        val remainingLength = buffer.readVariableByteInteger()
        val result = PublishMessage.from(buffer, byte1, remainingLength)
        assertMessageIsSame(publishMessage, result)
    }

    @Test
    fun genericSerializationPublishRetainFlag() {
        val publishMessage =
            PublishMessage.buildPayload(
                topicName = checkNotNull(Topic.fromOrThrow("user/log", Topic.Type.Name)),
                payload = payload,
                retain = true,
            )
        val buffer = PlatformBuffer.allocate(16)
        publishMessage.serialize(buffer)
        publishMessage.payload?.position(0)
        buffer.resetForRead()
        val firstByte = buffer.readUnsignedByte()
        assertEquals(firstByte.toInt().shr(4), 3, "fixed header control packet type")
        assertFalse(firstByte.get(3), "fixed header publish dup flag")
        assertFalse(firstByte.get(2), "fixed header qos bit 2")
        assertFalse(firstByte.get(1), "fixed header qos bit 1")
        assertTrue(firstByte.get(0), "fixed header retain flag")
        assertEquals(buffer.readVariableByteInteger(), 14, "fixed header remaining length")
        assertEquals(8u, buffer.readUnsignedShort(), "variable header topic name length")
        assertEquals(
            checkNotNull(Topic.fromOrThrow("user/log", Topic.Type.Name)).toString(),
            buffer.readString(8, Charset.UTF8),
            "variable header topic name value",
        )
        if (publishMessage.variable.packetIdentifier in validControlPacketIdentifierRange) {
            assertEquals(
                buffer.readUnsignedShort().toInt(),
                publishMessage.variable.packetIdentifier,
            )
        }
        assertEquals("yolo", buffer.readString(4, Charset.UTF8), "payload value")
        buffer.resetForRead()
        val byte1 = buffer.readUnsignedByte()
        val remainingLength = buffer.readVariableByteInteger()
        val result = PublishMessage.from(buffer, byte1, remainingLength)
        assertMessageIsSame(publishMessage, result)
    }

    @Test
    fun nullGenericSerialization() {
        val publishMessage =
            PublishMessage.build(topicName = checkNotNull(Topic.fromOrThrow("user/log", Topic.Type.Name)))
        val buffer = PlatformBuffer.allocate(12)
        publishMessage.serialize(buffer)
        buffer.resetForRead()
        val firstByte = buffer.readUnsignedByte()
        assertEquals(firstByte.toInt().shr(4), 3, "fixed header control packet type")
        assertFalse(firstByte.get(3), "fixed header publish dup flag")
        assertFalse(firstByte.get(2), "fixed header qos bit 2")
        assertFalse(firstByte.get(1), "fixed header qos bit 1")
        assertFalse(firstByte.get(0), "fixed header retain flag")
        assertEquals(buffer.readVariableByteInteger(), 10, "fixed header remaining length")
        assertEquals(8u, buffer.readUnsignedShort(), "variable header topic name length")
        assertEquals(
            checkNotNull(Topic.fromOrThrow("user/log", Topic.Type.Name)).toString(),
            buffer.readString(8, Charset.UTF8),
            "variable header topic name value",
        )
        if (publishMessage.variable.packetIdentifier in validControlPacketIdentifierRange) {
            assertEquals(
                buffer.readUnsignedShort().toInt(),
                publishMessage.variable.packetIdentifier,
            )
        }
        buffer.resetForRead()
        val byte1 = buffer.readUnsignedByte()
        val remainingLength = buffer.readVariableByteInteger()
        val result = PublishMessage.from(buffer, byte1, remainingLength)
        publishMessage.payload?.resetForRead()
        assertEquals(publishMessage, result)
    }
}
