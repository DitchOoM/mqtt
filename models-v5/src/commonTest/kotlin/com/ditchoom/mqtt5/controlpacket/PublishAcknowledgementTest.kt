package com.ditchoom.mqtt5.controlpacket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
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
import com.ditchoom.mqtt5.controlpacket.properties.ReasonString
import com.ditchoom.mqtt5.controlpacket.properties.UserProperty
import com.ditchoom.mqtt5.controlpacket.properties.encodeProperty
import com.ditchoom.mqtt5.controlpacket.properties.encodedSize
import com.ditchoom.mqtt5.controlpacket.properties.readProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.fail

class PublishAcknowledgementTest {
    private val packetIdentifier = 2

    @Test
    fun packetIdentifier() {
        val puback = PublishAcknowledgment(AckVariableHeader(packetIdentifier))
        val buffer = BufferFactory.Default.allocate(4)
        puback.serialize(buffer)
        buffer.resetForRead()
        assertEquals(
            0b01000000,
            buffer.readUnsignedByte().toInt(),
            "fixed header invalid byte 1, packet identifier",
        )
        assertEquals(
            2,
            buffer.readVariableByteInteger(),
            "fixed header invalid byte 2, remaining length",
        )
        assertEquals(
            packetIdentifier.toUShort(),
            buffer.readUnsignedShort(),
            "variable header invalid byte 3-4, packet identifier",
        )
        buffer.resetForRead()
        val pubackResult = ControlPacketV5.from(buffer) as PublishAcknowledgment
        assertEquals(pubackResult.variable.packetIdentifier, packetIdentifier)
    }

    @Test
    fun packetIdentifierSendDefaults() {
        val puback = PublishAcknowledgment(AckVariableHeader(packetIdentifier))
        val buffer = BufferFactory.Default.allocate(4)
        puback.serialize(buffer)
        buffer.resetForRead()
        val pubackResult = ControlPacketV5.from(buffer) as PublishAcknowledgment
        assertEquals(pubackResult.variable.packetIdentifier, packetIdentifier)
    }

    @Test
    fun noMatchingSubscribers() {
        val puback =
            PublishAcknowledgment(AckVariableHeader(packetIdentifier, NO_MATCHING_SUBSCRIBERS))
        val buffer = BufferFactory.Default.allocate(6)
        puback.serialize(buffer)
        buffer.resetForRead()
        val pubackResult = ControlPacketV5.from(buffer) as PublishAcknowledgment
        assertEquals(pubackResult.variable.reasonCode, NO_MATCHING_SUBSCRIBERS)
    }

    @Test
    fun unspecifiedError() {
        val puback = PublishAcknowledgment(AckVariableHeader(packetIdentifier, UNSPECIFIED_ERROR))
        val buffer = BufferFactory.Default.allocate(6)
        puback.serialize(buffer)
        buffer.resetForRead()
        val pubackResult = ControlPacketV5.from(buffer) as PublishAcknowledgment
        assertEquals(pubackResult.variable.reasonCode, UNSPECIFIED_ERROR)
    }

    @Test
    fun implementationSpecificError() {
        val puback =
            PublishAcknowledgment(AckVariableHeader(packetIdentifier, IMPLEMENTATION_SPECIFIC_ERROR))
        val buffer = BufferFactory.Default.allocate(6)
        puback.serialize(buffer)
        buffer.resetForRead()
        val pubackResult = ControlPacketV5.from(buffer) as PublishAcknowledgment
        assertEquals(pubackResult.variable.reasonCode, IMPLEMENTATION_SPECIFIC_ERROR)
    }

    @Test
    fun notAuthorized() {
        val puback = PublishAcknowledgment(AckVariableHeader(packetIdentifier, NOT_AUTHORIZED))
        val buffer = BufferFactory.Default.allocate(6)
        puback.serialize(buffer)
        buffer.resetForRead()
        val pubackResult = ControlPacketV5.from(buffer) as PublishAcknowledgment
        assertEquals(pubackResult.variable.reasonCode, NOT_AUTHORIZED)
    }

    @Test
    fun topicNameInvalid() {
        val puback = PublishAcknowledgment(AckVariableHeader(packetIdentifier, TOPIC_NAME_INVALID))
        val buffer = BufferFactory.Default.allocate(6)
        puback.serialize(buffer)
        buffer.resetForRead()
        val pubackResult = ControlPacketV5.from(buffer) as PublishAcknowledgment
        assertEquals(pubackResult.variable.reasonCode, TOPIC_NAME_INVALID)
    }

    @Test
    fun packetIdentifierInUse() {
        val puback =
            PublishAcknowledgment(AckVariableHeader(packetIdentifier, PACKET_IDENTIFIER_IN_USE))
        val buffer = BufferFactory.Default.allocate(6)
        puback.serialize(buffer)
        buffer.resetForRead()
        val pubackResult = ControlPacketV5.from(buffer) as PublishAcknowledgment
        assertEquals(pubackResult.variable.reasonCode, PACKET_IDENTIFIER_IN_USE)
    }

    @Test
    fun quotaExceeded() {
        val puback = PublishAcknowledgment(AckVariableHeader(packetIdentifier, QUOTA_EXCEEDED))
        val buffer = BufferFactory.Default.allocate(6)
        puback.serialize(buffer)
        buffer.resetForRead()
        val pubackResult = ControlPacketV5.from(buffer) as PublishAcknowledgment
        assertEquals(pubackResult.variable.reasonCode, QUOTA_EXCEEDED)
    }

    @Test
    fun payloadFormatInvalid() {
        val puback = PublishAcknowledgment(AckVariableHeader(packetIdentifier, PAYLOAD_FORMAT_INVALID))
        val buffer = BufferFactory.Default.allocate(6)
        puback.serialize(buffer)
        buffer.resetForRead()
        val pubackResult = ControlPacketV5.from(buffer) as PublishAcknowledgment
        assertEquals(pubackResult.variable.reasonCode, PAYLOAD_FORMAT_INVALID)
    }

    @Test
    fun invalidReasonCodeThrowsProtocolError() {
        assertFailsWith<IllegalArgumentException> {
            PublishAcknowledgment(AckVariableHeader(packetIdentifier, RECEIVE_MAXIMUM_EXCEEDED))
        }
    }

    @Test
    fun reasonString() {
        val expected =
            PublishAcknowledgment(
                AckVariableHeader(
                    packetIdentifier,
                    properties = AckProperties(reasonString = "yolo"),
                ),
            )
        val buffer = BufferFactory.Default.allocate(13)
        expected.serialize(buffer)
        buffer.resetForRead()
//        val actual = ControlPacketV5.from(buffer) as PublishAcknowledgment
        assertEquals(
            expected.variable.properties.reasonString
                .toString(),
            "yolo",
        )
    }

    @Test
    fun reasonStringMultipleTimesThrowsProtocolError() {
        val obj1 = ReasonString("yolo")
        val obj2 = obj1
        val buffer = BufferFactory.Default.allocate(15)
        buffer.writeVariableByteInteger(encodedSize(obj1) + encodedSize(obj2))
        encodeProperty(buffer, obj1)
        encodeProperty(buffer, obj2)
        buffer.resetForRead()
        assertFailsWith<com.ditchoom.mqtt.ProtocolError> {
            DisconnectNotification.VariableHeader.Properties.from(buffer.readProperties())
            fail()
        }
    }

    /**
     * Regression: remainingLength=3 means packetId (2) + reasonCode (1), no property length byte.
     * Before the fix, AckV5WireCodec.decode was called which expected a property length VBI,
     * causing a parse failure or reading garbage.
     */
    @Test
    fun remainingLength3ReasonCodeNoProperties() {
        // Manually construct: fixed header byte, RL=3, packetId (2 bytes), reason code (1 byte)
        val buffer = BufferFactory.Default.allocate(5)
        buffer.writeByte(0b01000000.toByte()) // PUBACK fixed header
        buffer.writeByte(3) // remaining length = 3
        buffer.writeUShort(packetIdentifier.toUShort()) // packet ID
        buffer.writeUByte(NO_MATCHING_SUBSCRIBERS.byte.toUByte()) // reason code, no properties
        buffer.resetForRead()
        val puback = ControlPacketV5.from(buffer) as PublishAcknowledgment
        assertEquals(packetIdentifier, puback.variable.packetIdentifier)
        assertEquals(NO_MATCHING_SUBSCRIBERS, puback.variable.reasonCode)
    }

    @Test
    fun variableHeaderPropertyUserProperty() {
        val props = AckProperties.from(setOf(UserProperty("key", "value")), "PUBACK")
        val userPropertyResult = props.userProperty
        for ((key, value) in userPropertyResult) {
            assertEquals(key, "key")
            assertEquals(value, "value")
        }
        assertEquals(userPropertyResult.size, 1)

        val request = PublishAcknowledgment(AckVariableHeader(packetIdentifier, properties = props))
        val buffer = BufferFactory.Default.allocate(19)
        request.serialize(buffer)
        buffer.resetForRead()
        val requestRead = ControlPacketV5.from(buffer) as PublishAcknowledgment
        val (key, value) =
            requestRead.variable.properties.userProperty
                .first()
        assertEquals(key.toString(), "key")
        assertEquals(value.toString(), "value")
    }

    // ── Decode from raw bytes ───────────────────────────────────────────────

    @Test
    fun pubackDecodeRemainingLength2FromRawBytes() {
        // 40 02 00 0A → packetId=10, implicit SUCCESS, no properties
        val buffer = BufferFactory.Default.allocate(4)
        buffer.writeByte(0x40.toByte())
        buffer.writeByte(0x02.toByte())
        buffer.writeUShort(10u)
        buffer.resetForRead()
        val puback = ControlPacketV5.from(buffer) as PublishAcknowledgment
        assertEquals(10, puback.variable.packetIdentifier)
        assertEquals(SUCCESS, puback.variable.reasonCode)
        assertEquals(null, puback.variable.properties.reasonString)
    }

    @Test
    fun pubackDecodeRemainingLength4FromRawBytes() {
        // 40 04 00 0A 00 00 → packetId=10, reasonCode=SUCCESS, propLen=0
        val buffer = BufferFactory.Default.allocate(6)
        buffer.writeByte(0x40.toByte())
        buffer.writeByte(0x04.toByte())
        buffer.writeUShort(10u)
        buffer.writeUByte(0x00u) // SUCCESS
        buffer.writeByte(0x00) // property length = 0
        buffer.resetForRead()
        val puback = ControlPacketV5.from(buffer) as PublishAcknowledgment
        assertEquals(10, puback.variable.packetIdentifier)
        assertEquals(SUCCESS, puback.variable.reasonCode)
    }
}
