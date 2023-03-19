package com.ditchoom.mqtt5.controlpacket

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.allocate
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.readMqttUtf8StringNotValidatedSized
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.readVariableByteInteger
import com.ditchoom.mqtt.controlpacket.Topic
import com.ditchoom.mqtt5.controlpacket.UnsubscribeRequest.VariableHeader
import com.ditchoom.mqtt5.controlpacket.properties.UserProperty
import kotlin.test.Test
import kotlin.test.assertEquals

class UnsubscribeRequestTests {

    private val packetIdentifier = 2

    @Test
    fun basicTest() {
        val buffer = PlatformBuffer.allocate(11)
        val unsub = UnsubscribeRequest(
            VariableHeader(packetIdentifier),
            setOf(Topic.fromOrThrow("yolo", Topic.Type.Filter))
        )
        unsub.serialize(buffer)
        buffer.resetForRead()
        assertEquals(0b10100010.toByte(), buffer.readByte(), "fixed header byte 1")
        assertEquals(9, buffer.readVariableByteInteger(), "fixed header byte 2 remaining length")
        assertEquals(
            packetIdentifier.toUShort(),
            buffer.readUnsignedShort(),
            "variable header byte 1-2 packet identifier"
        )
        assertEquals(0, buffer.readVariableByteInteger(), "variable header byte 3 property length")
        assertEquals(
            "yolo",
            buffer.readMqttUtf8StringNotValidatedSized().second.toString(),
            "payload topic"
        )
        buffer.resetForRead()
        val result = ControlPacketV5.from(buffer) as UnsubscribeRequest
        assertEquals("yolo", result.topics.first().toString())
        assertEquals(unsub.toString(), result.toString())
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

        val request =
            UnsubscribeRequest(
                VariableHeader(packetIdentifier, properties = props),
                setOf(Topic.fromOrThrow("test", Topic.Type.Filter))
            )
        val buffer = PlatformBuffer.allocate(24)
        request.serialize(buffer)
        buffer.resetForRead()
        val requestRead = ControlPacketV5.from(buffer) as UnsubscribeRequest
        val (key, value) = requestRead.variable.properties.userProperty.first()
        assertEquals("key", key.toString())
        assertEquals("value", value.toString())
    }
}
