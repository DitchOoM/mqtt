package com.ditchoom.mqtt5.controlpacket

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.allocate
import com.ditchoom.mqtt.ProtocolError
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.readMqttUtf8StringNotValidatedSized
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.readVariableByteInteger
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.writeVariableByteInteger
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.IMPLEMENTATION_SPECIFIC_ERROR
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.NOT_AUTHORIZED
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.NO_MATCHING_SUBSCRIBERS
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.PACKET_IDENTIFIER_IN_USE
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.PAYLOAD_FORMAT_INVALID
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.QUOTA_EXCEEDED
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.RECEIVE_MAXIMUM_EXCEEDED
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.SUCCESS
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.TOPIC_NAME_INVALID
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.UNSPECIFIED_ERROR
import com.ditchoom.mqtt5.controlpacket.PublishReceived.VariableHeader
import com.ditchoom.mqtt5.controlpacket.properties.ReasonString
import com.ditchoom.mqtt5.controlpacket.properties.UserProperty
import com.ditchoom.mqtt5.controlpacket.properties.readProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.fail

class PublishReceivedTests {
    private val packetIdentifier = 2

    @Test
    fun packetIdentifier() {
        val pubrec = PublishReceived(VariableHeader(packetIdentifier))
        val buffer = PlatformBuffer.allocate(4)
        pubrec.serialize(buffer)
        buffer.resetForRead()
        assertEquals(0b01010000, buffer.readByte(), "fixed header byte1")
        assertEquals(2, buffer.readVariableByteInteger(), "fixed header byte2 remaining length")
        assertEquals(
            packetIdentifier,
            buffer.readUnsignedShort().toInt(),
            "variable header byte 1-2",
        )
        buffer.resetForRead()
        val pubrecResult = ControlPacketV5.from(buffer) as PublishReceived
        assertEquals(pubrecResult.variable.packetIdentifier, packetIdentifier)
    }

    @Test
    fun defaultAndNonDefaultSuccessDeserialization() {
        val pubrec = PublishReceived(VariableHeader(packetIdentifier))
        val bufferNonDefaults = PlatformBuffer.allocate(6)
        bufferNonDefaults.writeByte(0b01010000.toByte())
        bufferNonDefaults.writeVariableByteInteger(4)
        bufferNonDefaults.writeUShort(packetIdentifier.toUShort())
        bufferNonDefaults.writeUByte(0.toUByte())
        bufferNonDefaults.writeVariableByteInteger(0)
        bufferNonDefaults.resetForRead()
        val pubrecResult = ControlPacketV5.from(bufferNonDefaults) as PublishReceived
        assertEquals(pubrec, pubrecResult)
    }

    @Test
    fun noMatchingSubscribers() {
        val pubrec = PublishReceived(VariableHeader(packetIdentifier, NO_MATCHING_SUBSCRIBERS))
        val buffer = PlatformBuffer.allocate(6)
        pubrec.serialize(buffer)
        buffer.resetForRead()
        assertEquals(0b01010000, buffer.readByte(), "fixed header byte1")
        assertEquals(4, buffer.readVariableByteInteger(), "fixed header byte2 remaining length")
        assertEquals(
            packetIdentifier,
            buffer.readUnsignedShort().toInt(),
            "variable header byte 1-2",
        )
        assertEquals(NO_MATCHING_SUBSCRIBERS.byte, buffer.readUnsignedByte(), "reason code")
        assertEquals(0, buffer.readVariableByteInteger(), "property length")
        buffer.resetForRead()
        val pubrecResult = ControlPacketV5.from(buffer) as PublishReceived
        assertEquals(pubrecResult.variable.reasonCode, NO_MATCHING_SUBSCRIBERS)
    }

    @Test
    fun unspecifiedError() {
        val pubrec = PublishReceived(VariableHeader(packetIdentifier, UNSPECIFIED_ERROR))
        val buffer = PlatformBuffer.allocate(6)
        pubrec.serialize(buffer)
        buffer.resetForRead()
        assertEquals(0b01010000, buffer.readByte(), "fixed header byte1")
        assertEquals(4, buffer.readVariableByteInteger(), "fixed header byte2 remaining length")
        assertEquals(
            packetIdentifier,
            buffer.readUnsignedShort().toInt(),
            "variable header byte 1-2",
        )
        assertEquals(UNSPECIFIED_ERROR.byte, buffer.readUnsignedByte(), "reason code")
        assertEquals(0, buffer.readVariableByteInteger(), "property length")
        buffer.resetForRead()
        val pubrecResult = ControlPacketV5.from(buffer) as PublishReceived
        assertEquals(pubrecResult.variable.reasonCode, UNSPECIFIED_ERROR)
    }

    @Test
    fun implementationSpecificError() {
        val pubrec =
            PublishReceived(VariableHeader(packetIdentifier, IMPLEMENTATION_SPECIFIC_ERROR))
        val buffer = PlatformBuffer.allocate(6)
        pubrec.serialize(buffer)
        buffer.resetForRead()
        assertEquals(0b01010000, buffer.readByte(), "fixed header byte1")
        assertEquals(4, buffer.readVariableByteInteger(), "fixed header byte2 remaining length")
        assertEquals(
            packetIdentifier,
            buffer.readUnsignedShort().toInt(),
            "variable header byte 1-2",
        )
        assertEquals(IMPLEMENTATION_SPECIFIC_ERROR.byte, buffer.readUnsignedByte(), "reason code")
        assertEquals(0, buffer.readVariableByteInteger(), "property length")
        buffer.resetForRead()
        val pubrecResult = ControlPacketV5.from(buffer) as PublishReceived
        assertEquals(pubrecResult.variable.reasonCode, IMPLEMENTATION_SPECIFIC_ERROR)
    }

    @Test
    fun notAuthorized() {
        val pubrec = PublishReceived(VariableHeader(packetIdentifier, NOT_AUTHORIZED))
        val buffer = PlatformBuffer.allocate(6)
        pubrec.serialize(buffer)
        buffer.resetForRead()
        assertEquals(0b01010000, buffer.readByte(), "fixed header byte1")
        assertEquals(4, buffer.readVariableByteInteger(), "fixed header byte2 remaining length")
        assertEquals(
            packetIdentifier,
            buffer.readUnsignedShort().toInt(),
            "variable header byte 1-2",
        )
        assertEquals(NOT_AUTHORIZED.byte, buffer.readUnsignedByte(), "reason code")
        assertEquals(0, buffer.readVariableByteInteger(), "property length")
        buffer.resetForRead()
        val pubrecResult = ControlPacketV5.from(buffer) as PublishReceived
        assertEquals(pubrecResult.variable.reasonCode, NOT_AUTHORIZED)
    }

    @Test
    fun topicNameInvalid() {
        val pubrec = PublishReceived(VariableHeader(packetIdentifier, TOPIC_NAME_INVALID))
        val buffer = PlatformBuffer.allocate(6)
        pubrec.serialize(buffer)
        buffer.resetForRead()
        assertEquals(0b01010000, buffer.readByte(), "fixed header byte1")
        assertEquals(4, buffer.readVariableByteInteger(), "fixed header byte2 remaining length")
        assertEquals(
            packetIdentifier,
            buffer.readUnsignedShort().toInt(),
            "variable header byte 1-2",
        )
        assertEquals(TOPIC_NAME_INVALID.byte, buffer.readUnsignedByte(), "reason code")
        assertEquals(0, buffer.readVariableByteInteger(), "property length")
        buffer.resetForRead()
        val pubrecResult = ControlPacketV5.from(buffer) as PublishReceived
        assertEquals(pubrecResult.variable.reasonCode, TOPIC_NAME_INVALID)
    }

    @Test
    fun packetIdentifierInUse() {
        val pubrec = PublishReceived(VariableHeader(packetIdentifier, PACKET_IDENTIFIER_IN_USE))
        val buffer = PlatformBuffer.allocate(6)
        pubrec.serialize(buffer)
        buffer.resetForRead()
        assertEquals(0b01010000, buffer.readByte(), "fixed header byte1")
        assertEquals(4, buffer.readVariableByteInteger(), "fixed header byte2 remaining length")
        assertEquals(
            packetIdentifier,
            buffer.readUnsignedShort().toInt(),
            "variable header byte 1-2",
        )
        assertEquals(PACKET_IDENTIFIER_IN_USE.byte, buffer.readUnsignedByte(), "reason code")
        assertEquals(0, buffer.readVariableByteInteger(), "property length")
        buffer.resetForRead()
        val pubrecResult = ControlPacketV5.from(buffer) as PublishReceived
        assertEquals(pubrecResult.variable.reasonCode, PACKET_IDENTIFIER_IN_USE)
    }

    @Test
    fun quotaExceeded() {
        val pubrec = PublishReceived(VariableHeader(packetIdentifier, QUOTA_EXCEEDED))
        val buffer = PlatformBuffer.allocate(6)
        pubrec.serialize(buffer)
        buffer.resetForRead()
        assertEquals(0b01010000, buffer.readByte(), "fixed header byte1")
        assertEquals(4, buffer.readVariableByteInteger(), "fixed header byte2 remaining length")
        assertEquals(
            packetIdentifier,
            buffer.readUnsignedShort().toInt(),
            "variable header byte 1-2",
        )
        assertEquals(QUOTA_EXCEEDED.byte, buffer.readUnsignedByte(), "reason code")
        assertEquals(0, buffer.readVariableByteInteger(), "property length")
        buffer.resetForRead()
        val pubrecResult = ControlPacketV5.from(buffer) as PublishReceived
        assertEquals(pubrecResult.variable.reasonCode, QUOTA_EXCEEDED)
    }

    @Test
    fun payloadFormatInvalid() {
        val pubrec = PublishReceived(VariableHeader(packetIdentifier, PAYLOAD_FORMAT_INVALID))
        val buffer = PlatformBuffer.allocate(6)
        pubrec.serialize(buffer)
        buffer.resetForRead()
        assertEquals(0b01010000, buffer.readByte(), "fixed header byte1")
        assertEquals(4, buffer.readVariableByteInteger(), "fixed header byte2 remaining length")
        assertEquals(
            packetIdentifier,
            buffer.readUnsignedShort().toInt(),
            "variable header byte 1-2",
        )
        assertEquals(PAYLOAD_FORMAT_INVALID.byte, buffer.readUnsignedByte(), "reason code")
        assertEquals(0, buffer.readVariableByteInteger(), "property length")
        buffer.resetForRead()
        val pubrecResult = ControlPacketV5.from(buffer) as PublishReceived
        assertEquals(pubrecResult.variable.reasonCode, PAYLOAD_FORMAT_INVALID)
    }

    @Test
    fun invalidReasonCodeThrowsProtocolError() {
        try {
            PublishReceived(VariableHeader(packetIdentifier, RECEIVE_MAXIMUM_EXCEEDED))
            fail()
        } catch (e: ProtocolError) {
        }
    }

    @Test
    fun reasonString() {
        val expected =
            PublishReceived(
                VariableHeader(
                    packetIdentifier,
                    properties = VariableHeader.Properties(reasonString = "yolo"),
                ),
            )
        val buffer = PlatformBuffer.allocate(13)
        expected.serialize(buffer)
        buffer.resetForRead()
        assertEquals(0b01010000, buffer.readByte(), "fixed header byte1")
        assertEquals(11, buffer.readVariableByteInteger(), "fixed header byte2 remaining length")
        assertEquals(
            packetIdentifier,
            buffer.readUnsignedShort().toInt(),
            "variable header byte 1-2",
        )
        assertEquals(SUCCESS.byte, buffer.readUnsignedByte(), "reason code")
        assertEquals(7, buffer.readVariableByteInteger(), "property length")
        assertEquals(0x1F, buffer.readByte(), "user property identifier")
        assertEquals(
            "yolo",
            buffer.readMqttUtf8StringNotValidatedSized().second.toString(),
            "reason string",
        )
        buffer.resetForRead()
        val pubrecResult = ControlPacketV5.from(buffer) as PublishReceived
        assertEquals(expected.variable.properties.reasonString.toString(), "yolo")
        assertEquals(expected.toString(), pubrecResult.toString())
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
        val props = VariableHeader.Properties.from(setOf(UserProperty("key", "value")))
        val userPropertyResult = props.userProperty
        for ((key, value) in userPropertyResult) {
            assertEquals(key, "key")
            assertEquals(value, "value")
        }
        assertEquals(userPropertyResult.size, 1)

        val buffer = PlatformBuffer.allocate(19)
        val request = PublishReceived(VariableHeader(packetIdentifier, properties = props))
        request.serialize(buffer)
        buffer.resetForRead()
        val requestRead = ControlPacketV5.from(buffer) as PublishReceived
        val (key, value) = requestRead.variable.properties.userProperty.first()
        assertEquals(key.toString(), "key")
        assertEquals(value.toString(), "value")
    }
}
