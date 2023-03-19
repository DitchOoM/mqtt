package com.ditchoom.mqtt5.controlpacket

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.allocate
import com.ditchoom.mqtt.ProtocolError
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.readVariableByteInteger
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.writeVariableByteInteger
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.IMPLEMENTATION_SPECIFIC_ERROR
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.NOT_AUTHORIZED
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.NO_MATCHING_SUBSCRIBERS
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.PACKET_IDENTIFIER_IN_USE
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.PAYLOAD_FORMAT_INVALID
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.QUOTA_EXCEEDED
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.RECEIVE_MAXIMUM_EXCEEDED
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.TOPIC_NAME_INVALID
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.UNSPECIFIED_ERROR
import com.ditchoom.mqtt5.controlpacket.PublishAcknowledgment.VariableHeader
import com.ditchoom.mqtt5.controlpacket.properties.ReasonString
import com.ditchoom.mqtt5.controlpacket.properties.UserProperty
import com.ditchoom.mqtt5.controlpacket.properties.readProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.fail

class PublishAcknowledgementTest {
    private val packetIdentifier = 2

    @Test
    fun packetIdentifier() {
        val puback = PublishAcknowledgment(VariableHeader(packetIdentifier))
        val buffer = PlatformBuffer.allocate(4)
        puback.serialize(buffer)
        buffer.resetForRead()
        assertEquals(
            0b01000000,
            buffer.readUnsignedByte().toInt(),
            "fixed header invalid byte 1, packet identifier"
        )
        assertEquals(
            2,
            buffer.readVariableByteInteger(),
            "fixed header invalid byte 2, remaining length"
        )
        assertEquals(
            packetIdentifier.toUShort(),
            buffer.readUnsignedShort(),
            "variable header invalid byte 3-4, packet identifier"
        )
        buffer.resetForRead()
        val pubackResult = ControlPacketV5.from(buffer) as PublishAcknowledgment
        assertEquals(pubackResult.variable.packetIdentifier, packetIdentifier)
    }

    @Test
    fun packetIdentifierSendDefaults() {
        val puback = PublishAcknowledgment(VariableHeader(packetIdentifier))
        val buffer = PlatformBuffer.allocate(4)
        puback.serialize(buffer)
        buffer.resetForRead()
        val pubackResult = ControlPacketV5.from(buffer) as PublishAcknowledgment
        assertEquals(pubackResult.variable.packetIdentifier, packetIdentifier)
    }

    @Test
    fun noMatchingSubscribers() {
        val puback =
            PublishAcknowledgment(VariableHeader(packetIdentifier, NO_MATCHING_SUBSCRIBERS))
        val buffer = PlatformBuffer.allocate(6)
        puback.serialize(buffer)
        buffer.resetForRead()
        val pubackResult = ControlPacketV5.from(buffer) as PublishAcknowledgment
        assertEquals(pubackResult.variable.reasonCode, NO_MATCHING_SUBSCRIBERS)
    }

    @Test
    fun unspecifiedError() {
        val puback = PublishAcknowledgment(VariableHeader(packetIdentifier, UNSPECIFIED_ERROR))
        val buffer = PlatformBuffer.allocate(6)
        puback.serialize(buffer)
        buffer.resetForRead()
        val pubackResult = ControlPacketV5.from(buffer) as PublishAcknowledgment
        assertEquals(pubackResult.variable.reasonCode, UNSPECIFIED_ERROR)
    }

    @Test
    fun implementationSpecificError() {
        val puback =
            PublishAcknowledgment(VariableHeader(packetIdentifier, IMPLEMENTATION_SPECIFIC_ERROR))
        val buffer = PlatformBuffer.allocate(6)
        puback.serialize(buffer)
        buffer.resetForRead()
        val pubackResult = ControlPacketV5.from(buffer) as PublishAcknowledgment
        assertEquals(pubackResult.variable.reasonCode, IMPLEMENTATION_SPECIFIC_ERROR)
    }

    @Test
    fun notAuthorized() {
        val puback = PublishAcknowledgment(VariableHeader(packetIdentifier, NOT_AUTHORIZED))
        val buffer = PlatformBuffer.allocate(6)
        puback.serialize(buffer)
        buffer.resetForRead()
        val pubackResult = ControlPacketV5.from(buffer) as PublishAcknowledgment
        assertEquals(pubackResult.variable.reasonCode, NOT_AUTHORIZED)
    }

    @Test
    fun topicNameInvalid() {
        val puback = PublishAcknowledgment(VariableHeader(packetIdentifier, TOPIC_NAME_INVALID))
        val buffer = PlatformBuffer.allocate(6)
        puback.serialize(buffer)
        buffer.resetForRead()
        val pubackResult = ControlPacketV5.from(buffer) as PublishAcknowledgment
        assertEquals(pubackResult.variable.reasonCode, TOPIC_NAME_INVALID)
    }

    @Test
    fun packetIdentifierInUse() {
        val puback =
            PublishAcknowledgment(VariableHeader(packetIdentifier, PACKET_IDENTIFIER_IN_USE))
        val buffer = PlatformBuffer.allocate(6)
        puback.serialize(buffer)
        buffer.resetForRead()
        val pubackResult = ControlPacketV5.from(buffer) as PublishAcknowledgment
        assertEquals(pubackResult.variable.reasonCode, PACKET_IDENTIFIER_IN_USE)
    }

    @Test
    fun quotaExceeded() {
        val puback = PublishAcknowledgment(VariableHeader(packetIdentifier, QUOTA_EXCEEDED))
        val buffer = PlatformBuffer.allocate(6)
        puback.serialize(buffer)
        buffer.resetForRead()
        val pubackResult = ControlPacketV5.from(buffer) as PublishAcknowledgment
        assertEquals(pubackResult.variable.reasonCode, QUOTA_EXCEEDED)
    }

    @Test
    fun payloadFormatInvalid() {
        val puback = PublishAcknowledgment(VariableHeader(packetIdentifier, PAYLOAD_FORMAT_INVALID))
        val buffer = PlatformBuffer.allocate(6)
        puback.serialize(buffer)
        buffer.resetForRead()
        val pubackResult = ControlPacketV5.from(buffer) as PublishAcknowledgment
        assertEquals(pubackResult.variable.reasonCode, PAYLOAD_FORMAT_INVALID)
    }

    @Test
    fun invalidReasonCodeThrowsProtocolError() {
        try {
            PublishAcknowledgment(VariableHeader(packetIdentifier, RECEIVE_MAXIMUM_EXCEEDED))
            fail()
        } catch (e: ProtocolError) {
        }
    }

    @Test
    fun reasonString() {
        val expected = PublishAcknowledgment(
            VariableHeader(
                packetIdentifier,
                properties = VariableHeader.Properties(reasonString = "yolo")
            )
        )
        val buffer = PlatformBuffer.allocate(13)
        expected.serialize(buffer)
        buffer.resetForRead()
//        val actual = ControlPacketV5.from(buffer) as PublishAcknowledgment
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
        assertFailsWith<ProtocolError> {
            DisconnectNotification.VariableHeader.Properties.from(buffer.readProperties())
            fail()
        }
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

        val request = PublishAcknowledgment(VariableHeader(packetIdentifier, properties = props))
        val buffer = PlatformBuffer.allocate(19)
        request.serialize(buffer)
        buffer.resetForRead()
        val requestRead = ControlPacketV5.from(buffer) as PublishAcknowledgment
        val (key, value) = requestRead.variable.properties.userProperty.first()
        assertEquals(key.toString(), "key")
        assertEquals(value.toString(), "value")
    }
}
