package com.ditchoom.mqtt5.controlpacket

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.allocate
import com.ditchoom.mqtt.ProtocolError
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.readMqttUtf8StringNotValidatedSized
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.readVariableByteInteger
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.writeVariableByteInteger
import com.ditchoom.mqtt.controlpacket.format.ReasonCode
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.RECEIVE_MAXIMUM_EXCEEDED
import com.ditchoom.mqtt5.controlpacket.PublishRelease.VariableHeader
import com.ditchoom.mqtt5.controlpacket.properties.ReasonString
import com.ditchoom.mqtt5.controlpacket.properties.UserProperty
import com.ditchoom.mqtt5.controlpacket.properties.readProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.fail

class PublishReleaseTests {
    private val packetIdentifier = 2

    @Test
    fun packetIdentifier() {
        val pubrel = PublishRelease(VariableHeader(packetIdentifier))
        val buffer = PlatformBuffer.allocate(4)
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
        val pubrel = PublishRelease(VariableHeader(packetIdentifier))
        val bufferNonDefaults = PlatformBuffer.allocate(6)
        bufferNonDefaults.writeByte(0b01100010.toByte())
        bufferNonDefaults.writeVariableByteInteger(4)
        bufferNonDefaults.writeUShort(packetIdentifier.toUShort())
        bufferNonDefaults.writeUByte(0.toUByte())
        bufferNonDefaults.writeVariableByteInteger(0)
        bufferNonDefaults.resetForRead()
        val pubrelResult = ControlPacketV5.from(bufferNonDefaults) as PublishRelease
        assertEquals(pubrel, pubrelResult)
    }

    @Test
    fun invalidReasonCodeThrowsProtocolError() {
        try {
            PublishRelease(VariableHeader(packetIdentifier, RECEIVE_MAXIMUM_EXCEEDED))
            fail()
        } catch (e: ProtocolError) {
        }
    }

    @Test
    fun reasonString() {
        val expected =
            PublishRelease(
                VariableHeader(
                    packetIdentifier,
                    properties = VariableHeader.Properties(reasonString = "yolo"),
                ),
            )
        val buffer = PlatformBuffer.allocate(13)
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
        assertEquals(expected.variable.properties.reasonString.toString(), "yolo")
        assertEquals(expected.toString(), pubrelResult.toString())
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
        val request = PublishRelease(VariableHeader(packetIdentifier, properties = props))
        request.serialize(buffer)
        buffer.resetForRead()
        val requestRead = ControlPacketV5.from(buffer) as PublishRelease
        val (key, value) = requestRead.variable.properties.userProperty.first()
        assertEquals(key.toString(), "key")
        assertEquals(value.toString(), "value")
    }
}
