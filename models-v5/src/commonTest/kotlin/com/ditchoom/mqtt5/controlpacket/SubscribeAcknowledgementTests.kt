package com.ditchoom.mqtt5.controlpacket

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.allocate
import com.ditchoom.mqtt.MalformedPacketException
import com.ditchoom.mqtt.ProtocolError
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.writeVariableByteInteger
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.BANNED
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.GRANTED_QOS_0
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.GRANTED_QOS_1
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.GRANTED_QOS_2
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.IMPLEMENTATION_SPECIFIC_ERROR
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.MALFORMED_PACKET
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.NOT_AUTHORIZED
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.PACKET_IDENTIFIER_IN_USE
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.QUOTA_EXCEEDED
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.SHARED_SUBSCRIPTIONS_NOT_SUPPORTED
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.SUBSCRIPTION_IDENTIFIERS_NOT_SUPPORTED
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.TOPIC_FILTER_INVALID
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.UNSPECIFIED_ERROR
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.WILDCARD_SUBSCRIPTIONS_NOT_SUPPORTED
import com.ditchoom.mqtt5.controlpacket.SubscribeAcknowledgement.VariableHeader
import com.ditchoom.mqtt5.controlpacket.SubscribeAcknowledgement.VariableHeader.Properties.Companion.from
import com.ditchoom.mqtt5.controlpacket.properties.ReasonString
import com.ditchoom.mqtt5.controlpacket.properties.UserProperty
import com.ditchoom.mqtt5.controlpacket.properties.readProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.fail

class SubscribeAcknowledgementTests {
    private val packetIdentifier = 2

    @Test
    fun packetIdentifier() {
        val payload = GRANTED_QOS_0
        val suback = SubscribeAcknowledgement(packetIdentifier.toUShort(), payload)
        val buffer = PlatformBuffer.allocate(6)
        suback.serialize(buffer)
        buffer.resetForRead()
        val subackResult = ControlPacketV5.from(buffer) as SubscribeAcknowledgement
        assertEquals(subackResult.variable.packetIdentifier, packetIdentifier)
        assertEquals(subackResult.payload.first(), GRANTED_QOS_0)
    }

    @Test
    fun grantedQos1() {
        val payload = GRANTED_QOS_1
        val obj = SubscribeAcknowledgement(packetIdentifier.toUShort(), payload)
        val buffer = PlatformBuffer.allocate(6)
        obj.serialize(buffer)
        buffer.resetForRead()
        val result = ControlPacketV5.from(buffer) as SubscribeAcknowledgement
        assertEquals(result.payload.first(), GRANTED_QOS_1)
    }

    @Test
    fun grantedQos2() {
        val payload = GRANTED_QOS_2
        val obj = SubscribeAcknowledgement(packetIdentifier.toUShort(), payload)
        val buffer = PlatformBuffer.allocate(6)
        obj.serialize(buffer)
        buffer.resetForRead()
        val result = ControlPacketV5.from(buffer) as SubscribeAcknowledgement
        assertEquals(result.payload.first(), GRANTED_QOS_2)
    }

    @Test
    fun unspecifiedError() {
        val payload = UNSPECIFIED_ERROR
        val obj = SubscribeAcknowledgement(packetIdentifier.toUShort(), payload)
        val buffer = PlatformBuffer.allocate(6)
        obj.serialize(buffer)
        buffer.resetForRead()
        val result = ControlPacketV5.from(buffer) as SubscribeAcknowledgement
        assertEquals(result.payload.first(), UNSPECIFIED_ERROR)
    }

    @Test
    fun implementationSpecificError() {
        val payload = IMPLEMENTATION_SPECIFIC_ERROR
        val obj = SubscribeAcknowledgement(packetIdentifier.toUShort(), payload)
        val buffer = PlatformBuffer.allocate(6)
        obj.serialize(buffer)
        buffer.resetForRead()
        val result = ControlPacketV5.from(buffer) as SubscribeAcknowledgement
        assertEquals(result.payload.first(), IMPLEMENTATION_SPECIFIC_ERROR)
    }

    @Test
    fun notAuthorized() {
        val payload = NOT_AUTHORIZED
        val obj = SubscribeAcknowledgement(packetIdentifier.toUShort(), payload)
        val buffer = PlatformBuffer.allocate(6)
        obj.serialize(buffer)
        buffer.resetForRead()
        val result = ControlPacketV5.from(buffer) as SubscribeAcknowledgement
        assertEquals(result.payload.first(), NOT_AUTHORIZED)
    }

    @Test
    fun topicFilterInvalid() {
        val payload = TOPIC_FILTER_INVALID
        val obj = SubscribeAcknowledgement(packetIdentifier.toUShort(), payload)
        val buffer = PlatformBuffer.allocate(6)
        obj.serialize(buffer)
        buffer.resetForRead()
        val result = ControlPacketV5.from(buffer) as SubscribeAcknowledgement
        assertEquals(result.payload.first(), TOPIC_FILTER_INVALID)
    }

    @Test
    fun packetIdentifierInUse() {
        val payload = PACKET_IDENTIFIER_IN_USE
        val obj = SubscribeAcknowledgement(packetIdentifier.toUShort(), payload)
        val buffer = PlatformBuffer.allocate(6)
        obj.serialize(buffer)
        buffer.resetForRead()
        val result = ControlPacketV5.from(buffer) as SubscribeAcknowledgement
        assertEquals(result.payload.first(), PACKET_IDENTIFIER_IN_USE)
    }

    @Test
    fun quotaExceeded() {
        val payload = QUOTA_EXCEEDED
        val obj = SubscribeAcknowledgement(packetIdentifier.toUShort(), payload)
        val buffer = PlatformBuffer.allocate(6)
        obj.serialize(buffer)
        buffer.resetForRead()
        val result = ControlPacketV5.from(buffer) as SubscribeAcknowledgement
        assertEquals(result.payload.first(), QUOTA_EXCEEDED)
    }

    @Test
    fun sharedSubscriptionsNotSupported() {
        val payload = SHARED_SUBSCRIPTIONS_NOT_SUPPORTED
        val obj = SubscribeAcknowledgement(packetIdentifier.toUShort(), payload)
        val buffer = PlatformBuffer.allocate(6)
        obj.serialize(buffer)
        buffer.resetForRead()
        val result = ControlPacketV5.from(buffer) as SubscribeAcknowledgement
        assertEquals(result.payload.first(), SHARED_SUBSCRIPTIONS_NOT_SUPPORTED)
    }

    @Test
    fun subscriptionIdentifiersNotSupported() {
        val payload = SUBSCRIPTION_IDENTIFIERS_NOT_SUPPORTED
        val obj = SubscribeAcknowledgement(packetIdentifier.toUShort(), payload)
        val buffer = PlatformBuffer.allocate(6)
        obj.serialize(buffer)
        buffer.resetForRead()
        val result = ControlPacketV5.from(buffer) as SubscribeAcknowledgement
        assertEquals(result.payload.first(), SUBSCRIPTION_IDENTIFIERS_NOT_SUPPORTED)
    }

    @Test
    fun wildcardSubscriptionsNotSupported() {
        val payload = WILDCARD_SUBSCRIPTIONS_NOT_SUPPORTED
        val obj = SubscribeAcknowledgement(packetIdentifier.toUShort(), payload)
        val buffer = PlatformBuffer.allocate(6)
        obj.serialize(buffer)
        buffer.resetForRead()
        val result = ControlPacketV5.from(buffer) as SubscribeAcknowledgement
        assertEquals(result.payload.first(), WILDCARD_SUBSCRIPTIONS_NOT_SUPPORTED)
    }

    @Test
    fun invalidVariableHeaderPayload() {
        val payload = MALFORMED_PACKET
        try {
            SubscribeAcknowledgement(packetIdentifier.toUShort(), payload)
            fail()
        } catch (e: ProtocolError) {
        }
    }

    @Test
    fun reasonString() {
        val props = VariableHeader.Properties(reasonString = "yolo")
        val actual = SubscribeAcknowledgement(packetIdentifier.toUShort(), props, GRANTED_QOS_1)
        val buffer = PlatformBuffer.allocate(13)
        actual.serialize(buffer)
        buffer.resetForRead()
        val result = ControlPacketV5.from(buffer) as SubscribeAcknowledgement
        assertEquals("yolo", result.variable.properties.reasonString.toString())
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
        assertFailsWith<ProtocolError> { PublishReceived.VariableHeader.Properties.from(buffer.readProperties()) }
    }

    @Test
    fun variableHeaderPropertyUserProperty() {
        val props = from(setOf(UserProperty("key", "value")))
        val userPropertyResult = props.userProperty
        for ((key, value) in userPropertyResult) {
            assertEquals(key, "key")
            assertEquals(value, "value")
        }
        assertEquals(userPropertyResult.size, 1)

        val request =
            SubscribeAcknowledgement(
                packetIdentifier.toUShort(),
                props,
                WILDCARD_SUBSCRIPTIONS_NOT_SUPPORTED,
            )
        val buffer = PlatformBuffer.allocate(19)
        request.serialize(buffer)
        buffer.resetForRead()
        val requestRead = ControlPacketV5.from(buffer) as SubscribeAcknowledgement
        val (key, value) = requestRead.variable.properties.userProperty.first()
        assertEquals("key", key.toString())
        assertEquals("value", value.toString())
    }

    @Test
    fun invalidReasonCode() {
        val variable = VariableHeader(packetIdentifier)
        val buffer = PlatformBuffer.allocate(5)
        variable.serialize(buffer)
        buffer.writeUByte(BANNED.byte)
        buffer.resetForRead()
        assertFailsWith<MalformedPacketException> { SubscribeAcknowledgement.from(buffer, 4) }
    }
}
