package com.ditchoom.mqtt5.controlpacket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.mqtt.ProtocolError
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.readMqttUtf8StringNotValidatedSized
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.readVariableByteInteger
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.writeVariableByteInteger
import com.ditchoom.mqtt.controlpacket.format.ReasonCode
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.RECEIVE_MAXIMUM_EXCEEDED
import com.ditchoom.mqtt5.controlpacket.properties.ReasonString
import com.ditchoom.mqtt5.controlpacket.properties.UserProperty
import com.ditchoom.mqtt5.controlpacket.properties.encodeProperty
import com.ditchoom.mqtt5.controlpacket.properties.encodedSize
import com.ditchoom.mqtt5.controlpacket.properties.readProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PublishReleaseTests {
    private val packetIdentifier = 2

    @Test
    fun packetIdentifier() {
        val pubrel = PublishRelease(AckVariableHeader(packetIdentifier))
        val buffer = BufferFactory.Default.allocate(4)
        pubrel.serialize(buffer)
        buffer.resetForRead()
        assertEquals(0b01100010, buffer.readByte(), "fixed header byte1")
        assertEquals(2, buffer.readVariableByteInteger(), "fixed header byte2 remaining length")
        assertEquals(
            packetIdentifier,
            buffer.readUnsignedShort().toInt(),
            "variable header byte 1-2",
        )
        buffer.resetForRead()
        val pubrelResult = ControlPacketV5.from(buffer) as PublishRelease
        assertEquals(pubrelResult.variable.packetIdentifier, packetIdentifier)
    }

    @Test
    fun defaultAndNonDefaultSuccessDeserialization() {
        val pubrel = PublishRelease(AckVariableHeader(packetIdentifier))
        val bufferNonDefaults = BufferFactory.Default.allocate(6)
        bufferNonDefaults.writeByte(0b01100010.toByte())
        bufferNonDefaults.writeVariableByteInteger(4)
        bufferNonDefaults.writeUShort(packetIdentifier.toUShort())
        bufferNonDefaults.writeUByte(0.toUByte())
        bufferNonDefaults.writeVariableByteInteger(0)
        bufferNonDefaults.resetForRead()
        val pubrelResult = ControlPacketV5.from(bufferNonDefaults) as PublishRelease
        assertEquals(pubrel, pubrelResult)
    }

    /**
     * Regression: remainingLength=3 means packetId (2) + reasonCode (1), no property length byte.
     * Before the fix, AckV5WireCodec.decode was called which expected a property length VBI.
     */
    @Test
    fun remainingLength3ReasonCodeNoProperties() {
        val buffer = BufferFactory.Default.allocate(5)
        buffer.writeByte(0b01100010.toByte()) // PUBREL fixed header
        buffer.writeByte(3) // remaining length = 3
        buffer.writeUShort(packetIdentifier.toUShort())
        buffer.writeUByte(ReasonCode.PACKET_IDENTIFIER_NOT_FOUND.byte.toUByte())
        buffer.resetForRead()
        val pubrel = ControlPacketV5.from(buffer) as PublishRelease
        assertEquals(packetIdentifier, pubrel.variable.packetIdentifier)
        assertEquals(ReasonCode.PACKET_IDENTIFIER_NOT_FOUND, pubrel.variable.reasonCode)
    }

    @Test
    fun invalidReasonCodeThrowsProtocolError() {
        assertFailsWith<IllegalArgumentException> {
            PublishRelease(AckVariableHeader(packetIdentifier, RECEIVE_MAXIMUM_EXCEEDED))
        }
    }

    @Test
    fun reasonString() {
        val expected =
            PublishRelease(
                AckVariableHeader(
                    packetIdentifier,
                    properties = AckProperties(reasonString = "yolo"),
                ),
            )
        val buffer = BufferFactory.Default.allocate(13)
        expected.serialize(buffer)
        buffer.resetForRead()
        assertEquals(0b01100010, buffer.readByte(), "fixed header byte1")
        assertEquals(11, buffer.readVariableByteInteger(), "fixed header byte2 remaining length")
        assertEquals(
            packetIdentifier,
            buffer.readUnsignedShort().toInt(),
            "variable header byte 1-2",
        )
        assertEquals(ReasonCode.SUCCESS.byte, buffer.readUnsignedByte(), "reason code")
        assertEquals(7, buffer.readVariableByteInteger(), "property length")
        assertEquals(0x1F, buffer.readByte(), "user property identifier")
        assertEquals(
            "yolo",
            buffer.readMqttUtf8StringNotValidatedSized().second.toString(),
            "reason string",
        )
        buffer.resetForRead()
        val pubrelResult = ControlPacketV5.from(buffer) as PublishRelease
        assertEquals(
            expected.variable.properties.reasonString
                .toString(),
            "yolo",
        )
        assertEquals(expected.toString(), pubrelResult.toString())
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
        assertFailsWith<ProtocolError> { AckProperties.from(buffer.readProperties(), "PUBREL") }
    }

    @Test
    fun variableHeaderPropertyUserProperty() {
        val props = AckProperties.from(setOf(UserProperty("key", "value")), "PUBREL")
        val userPropertyResult = props.userProperty
        for ((key, value) in userPropertyResult) {
            assertEquals(key, "key")
            assertEquals(value, "value")
        }
        assertEquals(userPropertyResult.size, 1)

        val buffer = BufferFactory.Default.allocate(19)
        val request = PublishRelease(AckVariableHeader(packetIdentifier, properties = props))
        request.serialize(buffer)
        buffer.resetForRead()
        val requestRead = ControlPacketV5.from(buffer) as PublishRelease
        val (key, value) =
            requestRead.variable.properties.userProperty
                .first()
        assertEquals(key.toString(), "key")
        assertEquals(value.toString(), "value")
    }

    // ── Decode from raw bytes ───────────────────────────────────────────────

    @Test
    fun pubrelDecodeRemainingLength2FromRawBytes() {
        // 62 02 00 0A → packetId=10, implicit SUCCESS (PUBREL flags=0010 → 0x62)
        val buffer = BufferFactory.Default.allocate(4)
        buffer.writeByte(0x62.toByte())
        buffer.writeByte(0x02.toByte())
        buffer.writeUShort(10u)
        buffer.resetForRead()
        val pubrel = ControlPacketV5.from(buffer) as PublishRelease
        assertEquals(10, pubrel.variable.packetIdentifier)
        assertEquals(ReasonCode.SUCCESS, pubrel.variable.reasonCode)
    }

    @Test
    fun pubrelDecodeRemainingLength4FromRawBytes() {
        // 62 04 00 0A 00 00 → packetId=10, SUCCESS, propLen=0
        val buffer = BufferFactory.Default.allocate(6)
        buffer.writeByte(0x62.toByte())
        buffer.writeByte(0x04.toByte())
        buffer.writeUShort(10u)
        buffer.writeUByte(0x00u)
        buffer.writeByte(0x00)
        buffer.resetForRead()
        val pubrel = ControlPacketV5.from(buffer) as PublishRelease
        assertEquals(10, pubrel.variable.packetIdentifier)
        assertEquals(ReasonCode.SUCCESS, pubrel.variable.reasonCode)
    }
}
