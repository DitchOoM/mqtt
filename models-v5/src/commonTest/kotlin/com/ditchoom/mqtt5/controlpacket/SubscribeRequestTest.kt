package com.ditchoom.mqtt5.controlpacket

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.allocate
import com.ditchoom.mqtt.ProtocolError
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.readMqttUtf8StringNotValidatedSized
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.readVariableByteInteger
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.writeVariableByteInteger
import com.ditchoom.mqtt.controlpacket.QualityOfService.AT_LEAST_ONCE
import com.ditchoom.mqtt.controlpacket.QualityOfService.EXACTLY_ONCE
import com.ditchoom.mqtt.controlpacket.Topic
import com.ditchoom.mqtt.controlpacket.validateMqttUTF8StringOrThrow
import com.ditchoom.mqtt5.controlpacket.SubscribeRequest.VariableHeader
import com.ditchoom.mqtt5.controlpacket.properties.ReasonString
import com.ditchoom.mqtt5.controlpacket.properties.UserProperty
import com.ditchoom.mqtt5.controlpacket.properties.readProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SubscribeRequestTest {
    private val packetIdentifier = 2.toUShort()

    @Test
    fun simpleTest() {
        val subscribeRequest = SubscribeRequest(2.toUShort(), "test", AT_LEAST_ONCE)
        assertEquals(subscribeRequest.variable.packetIdentifier, 2)
        assertEquals(
            subscribeRequest.subscriptions.first().topicFilter.toString().validateMqttUTF8StringOrThrow(),
            "test"
        )
        val buffer = PlatformBuffer.allocate(12)
        subscribeRequest.serialize(buffer)
        buffer.resetForRead()
        // fixed header 2 bytes
        // byte 1 fixed header
        assertEquals(0b10000010.toUByte(), buffer.readUnsignedByte())
        // byte 2 fixed header
        assertEquals(10, buffer.readVariableByteInteger())

        // Variable header 3 bytes
        // byte 1 & 2 variable header as Ushort for packet identifier
        assertEquals(packetIdentifier, buffer.readUnsignedShort())

        // byte 3 variable header, property length
        assertEquals(0.toUByte(), buffer.readUnsignedByte())

        // Payload 12 bytes
        // Topic Filter ("a/b")
        // byte 1: Length MSB (0)
        assertEquals(0b00000000, buffer.readByte())
        // byte2: Length LSB (4)
        assertEquals(4, buffer.readByte())
        // byte3: t
        assertEquals('t', buffer.readByte().toInt().toChar())
        // byte4: e
        assertEquals('e', buffer.readByte().toInt().toChar())
        // byte5: s
        assertEquals('s', buffer.readByte().toInt().toChar())
        // byte6: t
        assertEquals('t', buffer.readByte().toInt().toChar())
        // Subscription Options
        // byte7: Subscription Options (1)
        assertEquals(0b00000001, buffer.readByte())
        buffer.resetForRead()
        val requestRead = ControlPacketV5.from(buffer) as SubscribeRequest
        assertEquals(
            requestRead.subscriptions.first().topicFilter.toString().validateMqttUTF8StringOrThrow(),
            "test"
        )
        assertEquals(AT_LEAST_ONCE, requestRead.subscriptions.first().maximumQos)
    }

    @Test
    fun serialized() {
        val subscribeRequest = SubscribeRequest(
            2,
            listOf(Topic.fromOrThrow("a/b", Topic.Type.Name), Topic.fromOrThrow("c/d", Topic.Type.Name)),
            listOf(AT_LEAST_ONCE, EXACTLY_ONCE)
        )
        assertEquals(subscribeRequest.variable.packetIdentifier, 2)
        val buffer = PlatformBuffer.allocate(17)
        subscribeRequest.serialize(buffer)
        buffer.resetForRead()
        // fixed header 2 bytes
        // byte 1 fixed header
        assertEquals(0b10000010.toUByte(), buffer.readUnsignedByte())
        // byte 2 fixed header
        assertEquals(15, buffer.readVariableByteInteger())

        // Variable header 3 bytes
        // byte 1 & 2 variable header as Ushort for packet identifier
        assertEquals(packetIdentifier, buffer.readUnsignedShort())

        // byte 3 variable header, property length
        assertEquals(0.toUByte(), buffer.readUnsignedByte())

        // Payload 12 bytes
        // Topic Filter ("a/b")
        // byte 1: Length MSB (0)
        assertEquals(0b00000000, buffer.readByte())
        // byte2: Length LSB (3)
        assertEquals(0b00000011, buffer.readByte())
        // byte3: a (0x61)
        assertEquals(0b01100001, buffer.readByte())
        // byte4: / (0x2F)
        assertEquals(0b00101111, buffer.readByte())
        // byte5: b (0x62)
        assertEquals(0b01100010, buffer.readByte())
        // Subscription Options
        // byte6: Subscription Options (1)
        assertEquals(0b00000001, buffer.readByte())

        // Topic Filter ("c/d")
        // byte 1: Length MSB (0)
        assertEquals(0b00000000, buffer.readByte())
        // byte2: Length LSB (3)
        assertEquals(0b00000011, buffer.readByte())
        // byte3: c (0x63)
        assertEquals(0b01100011, buffer.readByte())
        // byte4: / (0x2F)
        assertEquals(0b00101111, buffer.readByte())
        // byte5: d (0x64)
        assertEquals(0b01100100, buffer.readByte())
        // Subscription Options
        // byte6: Subscription Options (2)
        assertEquals(0b00000010, buffer.readByte())
    }

    @Test
    fun subscriptionPayloadOptions() {
        val subscription = Subscription.from("a/b", AT_LEAST_ONCE)
        val buffer = PlatformBuffer.allocate(6)
        subscription.serialize(buffer)
        buffer.resetForRead()
        assertEquals("a/b", buffer.readMqttUtf8StringNotValidatedSized().second)
        assertEquals(0b000001, buffer.readByte())
    }

    @Test
    fun reasonString() {
        val buffer = PlatformBuffer.allocate(19)
        val actual = SubscribeRequest(
            VariableHeader(
                packetIdentifier.toInt(),
                properties = VariableHeader.Properties(reasonString = "yolo")
            ),
            setOf(Subscription(Topic.fromOrThrow("test", Topic.Type.Filter)))
        )
        actual.serialize(buffer)
        buffer.resetForRead()
        val expected = ControlPacketV5.from(buffer) as SubscribeRequest
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
        val props = VariableHeader.Properties.from(setOf(UserProperty("key", "value")))
        val userPropertyResult = props.userProperty
        for ((key, value) in userPropertyResult) {
            assertEquals(key, "key")
            assertEquals(value, "value")
        }
        assertEquals(userPropertyResult.size, 1)

        val request = SubscribeRequest(
            VariableHeader(packetIdentifier.toInt(), properties = props),
            setOf(Subscription(Topic.fromOrThrow("test", Topic.Type.Filter)))
        )
        val buffer = PlatformBuffer.allocate(25)
        request.serialize(buffer)
        buffer.resetForRead()
        val requestRead = ControlPacketV5.from(buffer) as SubscribeRequest
        val (key, value) = requestRead.variable.properties.userProperty.first()
        assertEquals("key", key)
        assertEquals("value", value)
    }
}
