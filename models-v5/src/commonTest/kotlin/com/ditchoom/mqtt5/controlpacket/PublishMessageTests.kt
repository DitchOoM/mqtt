package com.ditchoom.mqtt5.controlpacket

import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.allocate
import com.ditchoom.mqtt.MalformedPacketException
import com.ditchoom.mqtt.ProtocolError
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.readMqttUtf8StringNotValidatedSized
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.readVariableByteInteger
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.writeVariableByteInteger
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.Topic
import com.ditchoom.mqtt5.controlpacket.PublishMessage.VariableHeader
import com.ditchoom.mqtt5.controlpacket.properties.ContentType
import com.ditchoom.mqtt5.controlpacket.properties.CorrelationData
import com.ditchoom.mqtt5.controlpacket.properties.MessageExpiryInterval
import com.ditchoom.mqtt5.controlpacket.properties.PayloadFormatIndicator
import com.ditchoom.mqtt5.controlpacket.properties.ResponseTopic
import com.ditchoom.mqtt5.controlpacket.properties.SubscriptionIdentifier
import com.ditchoom.mqtt5.controlpacket.properties.TopicAlias
import com.ditchoom.mqtt5.controlpacket.properties.UserProperty
import com.ditchoom.mqtt5.controlpacket.properties.WillDelayInterval
import com.ditchoom.mqtt5.controlpacket.properties.readProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class PublishMessageTests {

    @Test
    fun serialize() {
        val buffer = PlatformBuffer.allocate(9)
        val expected = PublishMessage(topicName = "a", qos = QualityOfService.AT_LEAST_ONCE, packetIdentifier = 1)
        expected.serialize(buffer)
        buffer.resetForRead()
        assertEquals(0b00110010, buffer.readByte(), "fixed header byte 1")
        assertEquals(6, buffer.readVariableByteInteger(), "fixed header remaining length")
        assertEquals(
            "a",
            buffer.readMqttUtf8StringNotValidatedSized().second.toString(),
            "topic name"
        )
        assertEquals(1u, buffer.readUnsignedShort(), "packet identifier")
        assertEquals(0, buffer.readProperties()?.count() ?: 0, "properties")
        buffer.resetForRead()
        val actual = ControlPacketV5.from(buffer) as PublishMessage
        assertEquals(expected, actual)
    }

    @Test
    fun qosBothBitsSetTo1ThrowsMalformedPacketException() {
        val byte1 = 0b00111110.toByte()
        val remainingLength = 1
        val buffer = PlatformBuffer.allocate(3)
        buffer.writeByte(byte1)
        buffer.writeVariableByteInteger(remainingLength)
        buffer.writeByte(1)
        buffer.resetForRead()
        try {
            ControlPacketV5.from(buffer) as PublishMessage
            fail()
        } catch (e: MalformedPacketException) {
        }
    }

    @Test
    fun payloadFormatIndicatorDefault() {
        val buffer = PlatformBuffer.allocate(6)
        val expected = PublishMessage(variable = VariableHeader(Topic.fromOrThrow("t", Topic.Type.Name)))
        expected.serialize(buffer)
        buffer.resetForRead()
        assertEquals(0b00110000, buffer.readByte(), "fixed header byte 1")
        assertEquals(4, buffer.readVariableByteInteger(), "fixed header remaining length")
        assertEquals(
            "t",
            buffer.readMqttUtf8StringNotValidatedSized().second.toString(),
            "topic name"
        )
        assertEquals(0, buffer.readProperties()?.count() ?: 0, "properties")
        buffer.resetForRead()
        val publish = ControlPacketV5.from(buffer) as PublishMessage
        assertFalse(publish.variable.properties.payloadFormatIndicator)
    }

    @Test
    fun payloadFormatIndicatorTrue() {
        val props = VariableHeader.Properties(true)
        val variableHeader = VariableHeader(Topic.fromOrThrow("t", Topic.Type.Name), properties = props)
        val buffer = PlatformBuffer.allocate(8)
        val expected = PublishMessage(variable = variableHeader)
        expected.serialize(buffer)
        buffer.resetForRead()
        assertEquals(0b00110000, buffer.readByte(), "fixed header byte 1")
        assertEquals(6, buffer.readVariableByteInteger(), "fixed header remaining length")
        assertEquals(
            "t",
            buffer.readMqttUtf8StringNotValidatedSized().second.toString(),
            "topic name"
        )
        val propertiesActual = buffer.readProperties()
        assertEquals(1, propertiesActual?.count() ?: 0, "properties")
        assertEquals(
            props.payloadFormatIndicator,
            (propertiesActual?.first() as PayloadFormatIndicator).willMessageIsUtf8
        )
        buffer.resetForRead()
        val publish = ControlPacketV5.from(buffer) as PublishMessage
        assertTrue(publish.variable.properties.payloadFormatIndicator)
    }

    @Test
    fun payloadFormatIndicatorFalse() {
        val props = VariableHeader.Properties(false)
        val buffer = PlatformBuffer.allocate(6)
        val variableHeader = VariableHeader(Topic.fromOrThrow("t", Topic.Type.Name), properties = props)
        val expected = PublishMessage(variable = variableHeader)
        expected.serialize(buffer)
        buffer.resetForRead()
        assertEquals(0b00110000, buffer.readByte(), "fixed header byte 1")
        assertEquals(4, buffer.readVariableByteInteger(), "fixed header remaining length")
        assertEquals(
            "t",
            buffer.readMqttUtf8StringNotValidatedSized().second.toString(),
            "topic name"
        )
        val propertiesActual = buffer.readProperties()
        assertEquals(0, propertiesActual?.count() ?: 0, "properties")
        assertNull((propertiesActual?.firstOrNull() as? PayloadFormatIndicator)?.willMessageIsUtf8)
        buffer.resetForRead()
        val publish = ControlPacketV5.from(buffer) as PublishMessage
        assertFalse(publish.variable.properties.payloadFormatIndicator)
    }

    @Test
    fun payloadFormatIndicatorDuplicateThrowsProtocolError() {
        val obj1 = PayloadFormatIndicator(false)
        val obj2 = obj1.copy()
        val buffer = PlatformBuffer.allocate(5)
        buffer.writeVariableByteInteger(obj1.size() + obj2.size())
        obj1.write(buffer)
        obj2.write(buffer)
        buffer.resetForRead()
        try {
            VariableHeader.Properties.from(buffer.readProperties())
            fail()
        } catch (e: ProtocolError) {
        }
    }

    @Test
    fun messageExpiryInterval() {
        val props = VariableHeader.Properties(messageExpiryInterval = 2)
        val variableHeader = VariableHeader(Topic.fromOrThrow("t", Topic.Type.Name), properties = props)
        val buffer = PlatformBuffer.allocate(11)
        val msg = PublishMessage(variable = variableHeader)
        msg.serialize(buffer)
        buffer.resetForRead()
        assertEquals(0b00110000, buffer.readByte(), "fixed header byte 1")
        assertEquals(9, buffer.readVariableByteInteger(), "fixed header remaining length")
        assertEquals(
            "t",
            buffer.readMqttUtf8StringNotValidatedSized().second.toString(),
            "topic name"
        )
        val propertiesActual = buffer.readProperties()
        assertEquals(1, propertiesActual?.count() ?: 0, "properties")
        assertEquals(
            props.messageExpiryInterval,
            (propertiesActual?.firstOrNull() as? MessageExpiryInterval)?.seconds
        )
        buffer.resetForRead()
        val publish = ControlPacketV5.from(buffer) as PublishMessage
        assertEquals(2, publish.variable.properties.messageExpiryInterval)
    }

    @Test
    fun messageExpiryIntervalDuplicateThrowsProtocolError() {
        val obj1 = MessageExpiryInterval(2)
        val obj2 = obj1.copy()
        val buffer = PlatformBuffer.allocate(11)
        buffer.writeVariableByteInteger(obj1.size() + obj2.size())
        obj1.write(buffer)
        obj2.write(buffer)
        buffer.resetForRead()
        try {
            VariableHeader.Properties.from(buffer.readProperties())
            fail()
        } catch (e: ProtocolError) {
        }
    }

    @Test
    fun topicAlias() {
        val props = VariableHeader.Properties(topicAlias = 2)
        val variableHeader = VariableHeader(Topic.fromOrThrow("t", Topic.Type.Name), properties = props)
        val expected = PublishMessage(variable = variableHeader)
        val buffer = PlatformBuffer.allocate(9)
        expected.serialize(buffer)
        buffer.resetForRead()
        assertEquals(0b00110000, buffer.readByte(), "fixed header byte 1")
        assertEquals(7, buffer.readVariableByteInteger(), "fixed header remaining length")
        assertEquals(
            "t",
            buffer.readMqttUtf8StringNotValidatedSized().second.toString(),
            "topic name"
        )
        val propertiesActual = buffer.readProperties()
        assertEquals(1, propertiesActual?.count() ?: 0, "properties")
        assertEquals(2, (propertiesActual?.firstOrNull() as? TopicAlias)?.value)
        buffer.resetForRead()
        val publish = ControlPacketV5.from(buffer) as PublishMessage
        assertEquals(expected, publish)
    }

    @Test
    fun topicAliasZeroValueThrowsProtocolError() {
        try {
            VariableHeader.Properties(topicAlias = 0)
            fail()
        } catch (e: ProtocolError) {
        }
        assertFails {
            PublishMessage(
                variable = VariableHeader(
                    properties = VariableHeader.Properties(topicAlias = 0),
                    topicName = Topic.fromOrThrow("t", Topic.Type.Name)
                )
            )
        }
    }

    @Test
    fun topicAliasDuplicateThrowsProtocolError() {
        val obj1 = TopicAlias(2)
        val obj2 = obj1.copy()
        val buffer = PlatformBuffer.allocate(7)
        buffer.writeVariableByteInteger(obj1.size() + obj2.size())
        obj1.write(buffer)
        obj2.write(buffer)
        buffer.resetForRead()
        try {
            VariableHeader.Properties.from(buffer.readProperties())
            fail()
        } catch (e: ProtocolError) {
        }
    }

    @Test
    fun responseTopic() {
        val props = VariableHeader.Properties(responseTopic = Topic.fromOrThrow("t/as", Topic.Type.Name))
        val variableHeader = VariableHeader(Topic.fromOrThrow("t", Topic.Type.Name), properties = props)
        val buffer = PlatformBuffer.allocate(13)
        val actual = PublishMessage(variable = variableHeader)
        actual.serialize(buffer)
        buffer.resetForRead()
        assertEquals(0b00110000, buffer.readByte(), "fixed header byte 1")
        assertEquals(11, buffer.readVariableByteInteger(), "fixed header remaining length")
        assertEquals(
            "t",
            buffer.readMqttUtf8StringNotValidatedSized().second.toString(),
            "topic name"
        )
        assertEquals(7, buffer.readVariableByteInteger(), "property length")
        assertEquals(0x08, buffer.readByte(), "property identifier response topic")
        assertEquals(
            "t/as",
            buffer.readMqttUtf8StringNotValidatedSized().second.toString(),
            "response topic value"
        )
        buffer.resetForRead()
        val publish = ControlPacketV5.from(buffer) as PublishMessage
        assertEquals("t/as", publish.variable.properties.responseTopic?.toString())
    }

    @Test
    fun responseTopicDuplicateThrowsProtocolError() {
        val obj1 = ResponseTopic(Topic.fromOrThrow("t/as", Topic.Type.Name))
        val obj2 = obj1.copy()
        val buffer = PlatformBuffer.allocate(15)
        buffer.writeVariableByteInteger(obj1.size() + obj2.size())
        obj1.write(buffer)
        obj2.write(buffer)
        buffer.resetForRead()
        try {
            VariableHeader.Properties.from(buffer.readProperties())
            fail()
        } catch (e: ProtocolError) {
        }
    }

    val yoyoBuffer = PlatformBuffer.allocate(4)
        .also { it.writeString("yoyo", Charset.UTF8) }

    @Test
    fun correlationData() {
        val props = VariableHeader.Properties(correlationData = yoyoBuffer)
        val variableHeader = VariableHeader(Topic.fromOrThrow("t", Topic.Type.Name), properties = props)
        val buffer = PlatformBuffer.allocate(13)
        val actual = PublishMessage(variable = variableHeader)
        actual.serialize(buffer)
        buffer.resetForRead()
        assertEquals(0b00110000, buffer.readByte(), "fixed header byte 1")
        assertEquals(11, buffer.readVariableByteInteger(), "fixed header remaining length")
        assertEquals(
            "t",
            buffer.readMqttUtf8StringNotValidatedSized().second.toString(),
            "topic name"
        )
        assertEquals(7, buffer.readVariableByteInteger(), "property length")
        assertEquals(0x09, buffer.readByte(), "property identifier correlation data")
        assertEquals(
            4u,
            buffer.readUnsignedShort(),
            "property binary data size for correlation data"
        )
        assertEquals("yoyo", buffer.readString(4, Charset.UTF8), "correlation data payload")
        buffer.resetForRead()
        val publish = ControlPacketV5.from(buffer) as PublishMessage
        assertEquals(
            "yoyo",
            publish.variable.properties.correlationData?.readString(4, Charset.UTF8).toString()
        )
    }

    @Test
    fun correlationDataDuplicateThrowsProtocolError() {
        val obj1 = CorrelationData(yoyoBuffer)
        val obj2 = CorrelationData(yoyoBuffer)
        val buffer = PlatformBuffer.allocate(15)
        buffer.writeVariableByteInteger(obj1.size() + obj2.size())
        obj1.write(buffer)
        obj2.write(buffer)
        buffer.resetForRead()
        try {
            VariableHeader.Properties.from(buffer.readProperties())
            fail()
        } catch (e: ProtocolError) {
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

        val request =
            PublishMessage(variable = VariableHeader(Topic.fromOrThrow("t", Topic.Type.Name), properties = props))
        val buffer = PlatformBuffer.allocate(100)
        request.serialize(buffer)
        buffer.resetForRead()
        val requestRead = ControlPacketV5.from(buffer) as PublishMessage
        val (key, value) = requestRead.variable.properties.userProperty.first()
        assertEquals("key", key.toString())
        assertEquals("value", value.toString())
    }

    @Test
    fun subscriptionIdentifier() {
        val props = VariableHeader.Properties(subscriptionIdentifier = setOf(2))
        val variableHeader = VariableHeader(Topic.fromOrThrow("t", Topic.Type.Name), properties = props)
        val buffer = PlatformBuffer.allocate(8)
        val actual = PublishMessage(variable = variableHeader)
        actual.serialize(buffer)
        buffer.resetForRead()
        val publish = ControlPacketV5.from(buffer) as PublishMessage
        assertEquals(2, publish.variable.properties.subscriptionIdentifier.first())
    }

    @Test
    fun subscriptionIdentifierZeroThrowsProtocolError() {
        val obj1 = SubscriptionIdentifier(0)
        val buffer = PlatformBuffer.allocate(6)
        val size = obj1.size()
        buffer.writeVariableByteInteger(size)
        obj1.write(buffer)
        buffer.resetForRead()
        assertFailsWith<ProtocolError> { VariableHeader.Properties.from(buffer.readProperties()) }
    }

    @Test
    fun contentType() {
        val props = VariableHeader.Properties(contentType = "t/as")
        val variableHeader = VariableHeader(Topic.fromOrThrow("t", Topic.Type.Name), properties = props)
        val buffer = PlatformBuffer.allocate(13)
        val actual = PublishMessage(variable = variableHeader)
        actual.serialize(buffer)
        buffer.resetForRead()
        val publish = ControlPacketV5.from(buffer) as PublishMessage
        assertEquals("t/as", publish.variable.properties.contentType?.toString())
    }

    @Test
    fun contentTypeDuplicateThrowsProtocolError() {
        val obj1 = ContentType("t/as")
        val obj2 = obj1.copy()
        val buffer = PlatformBuffer.allocate(15)
        buffer.writeVariableByteInteger(obj1.size() + obj2.size())
        obj1.write(buffer)
        obj2.write(buffer)
        buffer.resetForRead()
        assertFailsWith<ProtocolError> { VariableHeader.Properties.from(buffer.readProperties()) }
    }

    @Test
    fun invalidPropertyOnVariableHeaderThrowsMalformedPacketException() {
        val method = WillDelayInterval(3)
        try {
            VariableHeader.Properties.from(listOf(method, method))
            fail()
        } catch (e: MalformedPacketException) {
        }
    }
}
