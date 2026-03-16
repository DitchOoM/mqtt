package com.ditchoom.mqtt5.controlpacket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.mqtt.ProtocolError
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.writeVariableByteInteger
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.PACKET_IDENTIFIER_NOT_FOUND
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.RECEIVE_MAXIMUM_EXCEEDED
import com.ditchoom.mqtt5.controlpacket.PublishComplete.VariableHeader
import com.ditchoom.mqtt5.controlpacket.properties.ReasonString
import com.ditchoom.mqtt5.controlpacket.properties.UserProperty
import com.ditchoom.mqtt5.controlpacket.properties.readProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.fail

class PublishCompleteTests {
    private val packetIdentifier = 2

    @Test
    fun packetIdentifier() {
        val pubcomp = PublishComplete(VariableHeader(packetIdentifier))
        val buffer = BufferFactory.Default.allocate(4)
        pubcomp.serialize(buffer)
        buffer.resetForRead()
        val pubcompResult = ControlPacketV5.from(buffer) as PublishComplete
        assertEquals(pubcompResult.variable.packetIdentifier, packetIdentifier)
    }

    @Test
    fun packetIdentifierSendDefaults() {
        val pubcomp = PublishComplete(VariableHeader(packetIdentifier))
        val buffer = BufferFactory.Default.allocate(4)
        pubcomp.serialize(buffer)
        buffer.resetForRead()
        val pubcompResult = ControlPacketV5.from(buffer) as PublishComplete
        assertEquals(pubcompResult.variable.packetIdentifier, packetIdentifier)
    }

    @Test
    fun noMatchingSubscribers() {
        val pubcomp = PublishComplete(VariableHeader(packetIdentifier, PACKET_IDENTIFIER_NOT_FOUND))
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
        try {
            PublishComplete(VariableHeader(packetIdentifier, RECEIVE_MAXIMUM_EXCEEDED))
            fail()
        } catch (e: ProtocolError) {
        }
    }

    @Test
    fun reasonString() {
        val expected =
            PublishComplete(
                VariableHeader(
                    packetIdentifier,
                    properties = VariableHeader.Properties(reasonString = "yolo"),
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
        val obj2 = obj1.copy()
        val buffer = BufferFactory.Default.allocate(35)
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
        val props =
            VariableHeader.Properties.from(
                setOf(
                    UserProperty(
                        "key",
                        "value",
                    ),
                ),
            )
        val userPropertyResult = props.userProperty
        for ((key, value) in userPropertyResult) {
            assertEquals(key, "key")
            assertEquals(value, "value")
        }
        assertEquals(userPropertyResult.size, 1)

        val buffer = BufferFactory.Default.allocate(19)
        val request = PublishComplete(VariableHeader(packetIdentifier, properties = props))
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
}
