package com.ditchoom.mqtt5.controlpacket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.mqtt.ProtocolError
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.writeVariableByteInteger
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.PACKET_IDENTIFIER_NOT_FOUND
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.RECEIVE_MAXIMUM_EXCEEDED
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.SUCCESS
import com.ditchoom.mqtt5.controlpacket.properties.ReasonString
import com.ditchoom.mqtt5.controlpacket.properties.UserProperty
import com.ditchoom.mqtt5.controlpacket.properties.encodeProperty
import com.ditchoom.mqtt5.controlpacket.properties.encodedSize
import com.ditchoom.mqtt5.controlpacket.properties.readProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.fail

class PublishCompleteTests {
    private val packetIdentifier = 2

    @Test
    fun packetIdentifier() {
        val pubcomp = PublishComplete(AckVariableHeader(packetIdentifier))
        val buffer = BufferFactory.Default.allocate(4)
        pubcomp.serialize(buffer)
        buffer.resetForRead()
        val pubcompResult = ControlPacketV5.from(buffer) as PublishComplete
        assertEquals(pubcompResult.variable.packetIdentifier, packetIdentifier)
    }

    @Test
    fun packetIdentifierSendDefaults() {
        val pubcomp = PublishComplete(AckVariableHeader(packetIdentifier))
        val buffer = BufferFactory.Default.allocate(4)
        pubcomp.serialize(buffer)
        buffer.resetForRead()
        val pubcompResult = ControlPacketV5.from(buffer) as PublishComplete
        assertEquals(pubcompResult.variable.packetIdentifier, packetIdentifier)
    }

    @Test
    fun noMatchingSubscribers() {
        val pubcomp = PublishComplete(AckVariableHeader(packetIdentifier, PACKET_IDENTIFIER_NOT_FOUND))
        val buffer = BufferFactory.Default.allocate(6)
        pubcomp.serialize(buffer)
        buffer.resetForRead()
        val pubcompResult = ControlPacketV5.from(buffer) as PublishComplete
        assertEquals(pubcompResult.variable.reasonCode, PACKET_IDENTIFIER_NOT_FOUND)
    }

    /**
     * Regression: remainingLength=3 means packetId (2) + reasonCode (1), no property length byte.
     * Before the fix, AckV5WireCodec.decode was called which expected a property length VBI.
     */
    @Test
    fun remainingLength3ReasonCodeNoProperties() {
        val buffer = BufferFactory.Default.allocate(5)
        buffer.writeByte(0b01110000.toByte()) // PUBCOMP fixed header
        buffer.writeByte(3) // remaining length = 3
        buffer.writeUShort(packetIdentifier.toUShort())
        buffer.writeUByte(PACKET_IDENTIFIER_NOT_FOUND.byte.toUByte())
        buffer.resetForRead()
        val pubcomp = ControlPacketV5.from(buffer) as PublishComplete
        assertEquals(packetIdentifier, pubcomp.variable.packetIdentifier)
        assertEquals(PACKET_IDENTIFIER_NOT_FOUND, pubcomp.variable.reasonCode)
    }

    @Test
    fun invalidReasonCodeThrowsProtocolError() {
        assertFailsWith<IllegalArgumentException> {
            PublishComplete(AckVariableHeader(packetIdentifier, RECEIVE_MAXIMUM_EXCEEDED))
        }
    }

    @Test
    fun reasonString() {
        val expected =
            PublishComplete(
                AckVariableHeader(
                    packetIdentifier,
                    properties = AckProperties(reasonString = "yolo"),
                ),
            )
        val buffer = BufferFactory.Default.allocate(13)
        expected.serialize(buffer)
        buffer.resetForRead()
//        val actual = ControlPacketV5.from(buffer) as PublishComplete
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
        val buffer = BufferFactory.Default.allocate(35)
        buffer.writeVariableByteInteger(encodedSize(obj1) + encodedSize(obj2))
        encodeProperty(buffer, obj1)
        encodeProperty(buffer, obj2)
        buffer.resetForRead()
        assertFailsWith<ProtocolError> {
            DisconnectNotification.VariableHeader.Properties.from(buffer.readProperties())
            fail()
        }
    }

    @Test
    fun variableHeaderPropertyUserProperty() {
        val props =
            AckProperties.from(
                setOf(
                    UserProperty(
                        "key",
                        "value",
                    ),
                ),
                "PUBCOMP",
            )
        val userPropertyResult = props.userProperty
        for ((key, value) in userPropertyResult) {
            assertEquals(key, "key")
            assertEquals(value, "value")
        }
        assertEquals(userPropertyResult.size, 1)

        val buffer = BufferFactory.Default.allocate(19)
        val request = PublishComplete(AckVariableHeader(packetIdentifier, properties = props))
        request.serialize(buffer)
        buffer.resetForRead()
        val requestRead = ControlPacketV5.from(buffer) as PublishComplete
        val (key, value) =
            requestRead.variable.properties.userProperty
                .first()
        assertEquals(key.toString(), "key")
        assertEquals(value.toString(), "value")
        assertEquals(request.toString(), requestRead.toString())
    }

    // ── Decode from raw bytes ───────────────────────────────────────────────

    @Test
    fun pubcompDecodeRemainingLength2FromRawBytes() {
        // 70 02 00 0A → packetId=10, implicit SUCCESS
        val buffer = BufferFactory.Default.allocate(4)
        buffer.writeByte(0x70.toByte())
        buffer.writeByte(0x02.toByte())
        buffer.writeUShort(10u)
        buffer.resetForRead()
        val pubcomp = ControlPacketV5.from(buffer) as PublishComplete
        assertEquals(10, pubcomp.variable.packetIdentifier)
        assertEquals(SUCCESS, pubcomp.variable.reasonCode)
    }

    @Test
    fun pubcompDecodeRemainingLength4FromRawBytes() {
        // 70 04 00 0A 00 00 → packetId=10, SUCCESS, propLen=0
        val buffer = BufferFactory.Default.allocate(6)
        buffer.writeByte(0x70.toByte())
        buffer.writeByte(0x04.toByte())
        buffer.writeUShort(10u)
        buffer.writeUByte(0x00u)
        buffer.writeByte(0x00)
        buffer.resetForRead()
        val pubcomp = ControlPacketV5.from(buffer) as PublishComplete
        assertEquals(10, pubcomp.variable.packetIdentifier)
        assertEquals(SUCCESS, pubcomp.variable.reasonCode)
    }
}
