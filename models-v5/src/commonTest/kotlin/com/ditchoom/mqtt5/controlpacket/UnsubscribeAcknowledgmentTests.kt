package com.ditchoom.mqtt5.controlpacket

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.allocate
import com.ditchoom.mqtt.ProtocolError
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.readMqttUtf8StringNotValidatedSized
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.readVariableByteInteger
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.writeVariableByteInteger
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.IMPLEMENTATION_SPECIFIC_ERROR
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.NOT_AUTHORIZED
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.NO_SUBSCRIPTIONS_EXISTED
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.PACKET_IDENTIFIER_IN_USE
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.SUCCESS
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.TOPIC_FILTER_INVALID
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.UNSPECIFIED_ERROR
import com.ditchoom.mqtt5.controlpacket.UnsubscribeAcknowledgment.VariableHeader
import com.ditchoom.mqtt5.controlpacket.properties.ReasonString
import com.ditchoom.mqtt5.controlpacket.properties.UserProperty
import com.ditchoom.mqtt5.controlpacket.properties.readProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.fail

class UnsubscribeAcknowledgmentTests {
    private val packetIdentifier = 2

    @Test
    fun serializeDeserializeDefault() {
        val actual = UnsubscribeAcknowledgment(VariableHeader(packetIdentifier))
        val buffer = PlatformBuffer.allocate(6)
        actual.serialize(buffer)
        buffer.resetForRead()
        assertEquals(0b10110000.toByte(), buffer.readByte(), "fixed header byte 1")
        assertEquals(4, buffer.readVariableByteInteger(), "fixed header byte 2 remaining length")
        assertEquals(0, buffer.readByte(), "variable header byte 1 packet identifier msb")
        assertEquals(2, buffer.readByte(), "variable header byte 2 packet identifier lsb")
        assertEquals(0, buffer.readVariableByteInteger(), "property length")
        assertEquals(SUCCESS.byte, buffer.readUnsignedByte(), "payload reason code")
        buffer.resetForRead()
        val expected = ControlPacketV5.from(buffer)
        assertEquals(expected, actual)
    }

    @Test
    fun serializeDeserializeNoSubscriptionsExisted() {
        val actual = UnsubscribeAcknowledgment(
            VariableHeader(packetIdentifier),
            listOf(NO_SUBSCRIPTIONS_EXISTED)
        )
        val buffer = PlatformBuffer.allocate(6)
        actual.serialize(buffer)
        buffer.resetForRead()
        assertEquals(0b10110000.toByte(), buffer.readByte(), "fixed header byte 1")
        assertEquals(4, buffer.readVariableByteInteger(), "fixed header byte 2 remaining length")
        assertEquals(0, buffer.readByte(), "variable header byte 1 packet identifier msb")
        assertEquals(2, buffer.readByte(), "variable header byte 2 packet identifier lsb")
        assertEquals(0, buffer.readVariableByteInteger(), "property length")
        assertEquals(
            NO_SUBSCRIPTIONS_EXISTED.byte,
            buffer.readUnsignedByte(),
            "payload reason code"
        )
        buffer.resetForRead()
        val expected = ControlPacketV5.from(buffer)
        assertEquals(expected, actual)
    }

    @Test
    fun serializeDeserializeUnspecifiedError() {
        val actual =
            UnsubscribeAcknowledgment(VariableHeader(packetIdentifier), listOf(UNSPECIFIED_ERROR))
        val buffer = PlatformBuffer.allocate(6)
        actual.serialize(buffer)
        buffer.resetForRead()
        assertEquals(0b10110000.toByte(), buffer.readByte(), "fixed header byte 1")
        assertEquals(4, buffer.readVariableByteInteger(), "fixed header byte 2 remaining length")
        assertEquals(0, buffer.readByte(), "variable header byte 1 packet identifier msb")
        assertEquals(2, buffer.readByte(), "variable header byte 2 packet identifier lsb")
        assertEquals(0, buffer.readVariableByteInteger(), "property length")
        assertEquals(UNSPECIFIED_ERROR.byte, buffer.readUnsignedByte(), "payload reason code")
        buffer.resetForRead()
        val expected = ControlPacketV5.from(buffer)
        assertEquals(expected, actual)
    }

    @Test
    fun serializeDeserializeImplementationSpecificError() {
        val actual = UnsubscribeAcknowledgment(
            VariableHeader(packetIdentifier),
            listOf(IMPLEMENTATION_SPECIFIC_ERROR)
        )
        val buffer = PlatformBuffer.allocate(6)
        actual.serialize(buffer)
        buffer.resetForRead()
        assertEquals(0b10110000.toByte(), buffer.readByte(), "fixed header byte 1")
        assertEquals(4, buffer.readVariableByteInteger(), "fixed header byte 2 remaining length")
        assertEquals(0, buffer.readByte(), "variable header byte 1 packet identifier msb")
        assertEquals(2, buffer.readByte(), "variable header byte 2 packet identifier lsb")
        assertEquals(0, buffer.readVariableByteInteger(), "property length")
        assertEquals(
            IMPLEMENTATION_SPECIFIC_ERROR.byte,
            buffer.readUnsignedByte(),
            "payload reason code"
        )
        buffer.resetForRead()
        val expected = ControlPacketV5.from(buffer)
        assertEquals(expected, actual)
    }

    @Test
    fun serializeDeserializeNotAuthorized() {
        val actual =
            UnsubscribeAcknowledgment(VariableHeader(packetIdentifier), listOf(NOT_AUTHORIZED))
        val buffer = PlatformBuffer.allocate(6)
        actual.serialize(buffer)
        buffer.resetForRead()
        assertEquals(0b10110000.toByte(), buffer.readByte(), "fixed header byte 1")
        assertEquals(4, buffer.readVariableByteInteger(), "fixed header byte 2 remaining length")
        assertEquals(0, buffer.readByte(), "variable header byte 1 packet identifier msb")
        assertEquals(2, buffer.readByte(), "variable header byte 2 packet identifier lsb")
        assertEquals(0, buffer.readVariableByteInteger(), "property length")
        assertEquals(NOT_AUTHORIZED.byte, buffer.readUnsignedByte(), "payload reason code")
        buffer.resetForRead()
        val expected = ControlPacketV5.from(buffer)
        assertEquals(expected, actual)
    }

    @Test
    fun serializeDeserializeTopicFilterInvalid() {
        val actual = UnsubscribeAcknowledgment(
            VariableHeader(packetIdentifier),
            listOf(TOPIC_FILTER_INVALID)
        )
        val buffer = PlatformBuffer.allocate(6)
        actual.serialize(buffer)
        buffer.resetForRead()
        assertEquals(0b10110000.toByte(), buffer.readByte(), "fixed header byte 1")
        assertEquals(4, buffer.readVariableByteInteger(), "fixed header byte 2 remaining length")
        assertEquals(0, buffer.readByte(), "variable header byte 1 packet identifier msb")
        assertEquals(2, buffer.readByte(), "variable header byte 2 packet identifier lsb")
        assertEquals(0, buffer.readVariableByteInteger(), "property length")
        assertEquals(TOPIC_FILTER_INVALID.byte, buffer.readUnsignedByte(), "payload reason code")
        buffer.resetForRead()
        val expected = ControlPacketV5.from(buffer)
        assertEquals(expected, actual)
    }

    @Test
    fun emptyReasonCodesThrowsProtocolError() {
        try {
            UnsubscribeAcknowledgment(VariableHeader(packetIdentifier), listOf())
            fail()
        } catch (e: ProtocolError) {
        }
    }

    @Test
    fun serializeDeserializePacketIdentifierInUse() {
        val actual = UnsubscribeAcknowledgment(
            VariableHeader(packetIdentifier),
            listOf(PACKET_IDENTIFIER_IN_USE)
        )
        val buffer = PlatformBuffer.allocate(6)
        actual.serialize(buffer)
        buffer.resetForRead()
        assertEquals(0b10110000.toByte(), buffer.readByte(), "fixed header byte 1")
        assertEquals(4, buffer.readVariableByteInteger(), "fixed header byte 2 remaining length")
        assertEquals(0, buffer.readByte(), "variable header byte 1 packet identifier msb")
        assertEquals(2, buffer.readByte(), "variable header byte 2 packet identifier lsb")
        assertEquals(0, buffer.readVariableByteInteger(), "property length")
        assertEquals(
            PACKET_IDENTIFIER_IN_USE.byte,
            buffer.readUnsignedByte(),
            "payload reason code"
        )
        buffer.resetForRead()
        val expected = ControlPacketV5.from(buffer)
        assertEquals(expected, actual)
    }

    @Test
    fun reasonString() {
        val props = VariableHeader.Properties(reasonString = "yolo")
        val header = VariableHeader(packetIdentifier, properties = props)
        val actual = UnsubscribeAcknowledgment(header)
        val buffer = PlatformBuffer.allocate(13)
        actual.serialize(buffer)
        buffer.resetForRead()
        assertEquals(0b10110000.toByte(), buffer.readByte(), "fixed header byte 1")
        assertEquals(11, buffer.readVariableByteInteger(), "fixed header byte 2 remaining length")
        assertEquals(0, buffer.readByte(), "variable header byte 1 packet identifier msb")
        assertEquals(2, buffer.readByte(), "variable header byte 2 packet identifier lsb")
        assertEquals(7, buffer.readVariableByteInteger(), "property length")
        assertEquals(0x1F, buffer.readByte(), "property type matching reason code")
        assertEquals(
            "yolo",
            buffer.readMqttUtf8StringNotValidatedSized().second.toString(),
            "reason code value"
        )
        assertEquals(SUCCESS.byte, buffer.readUnsignedByte(), "payload reason code")
        buffer.resetForRead()
        val expected = ControlPacketV5.from(buffer) as UnsubscribeAcknowledgment
        assertEquals(expected.variable.properties.reasonString.toString(), "yolo")
    }

    @Test
    fun reasonStringMultipleTimesThrowsProtocolError() {
        val obj1 = ReasonString("yolo")
        val obj2 = obj1.copy()
        val buffer = PlatformBuffer.allocate(15)
        buffer.writeVariableByteInteger(obj1.size() + obj2.size())
        obj1.write(buffer)
        obj2.write(buffer)
        buffer.resetForRead()
        assertFailsWith<ProtocolError> { VariableHeader.Properties.from(buffer.readProperties()) }
    }

    @Test
    fun variableHeaderPropertyUserProperty() {
        val props = VariableHeader.Properties.from(
            setOf(
                UserProperty("key", "value"),
                UserProperty("key", "value")
            )
        )
        val userPropertyResult = props.userProperty
        for ((key, value) in userPropertyResult) {
            assertEquals(key, "key")
            assertEquals(value, "value")
        }
        assertEquals(userPropertyResult.size, 1)

        val request =
            UnsubscribeAcknowledgment(VariableHeader(packetIdentifier, properties = props))
        val buffer = PlatformBuffer.allocate(19)
        request.serialize(buffer)
        buffer.resetForRead()
        val requestRead = ControlPacketV5.from(buffer) as UnsubscribeAcknowledgment
        val (key, value) = requestRead.variable.properties.userProperty.first()
        assertEquals("key", key.toString())
        assertEquals("value", value.toString())
    }
}
