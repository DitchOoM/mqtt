package com.ditchoom.mqtt5.controlpacket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.mqtt.MalformedPacketException
import com.ditchoom.mqtt.ProtocolError
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.readMqttUtf8StringNotValidatedSized
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.readVariableByteInteger
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.writeVariableByteInteger
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.TopicName
import com.ditchoom.mqtt5.controlpacket.properties.ContentType
import com.ditchoom.mqtt5.controlpacket.properties.CorrelationData
import com.ditchoom.mqtt5.controlpacket.properties.MessageExpiryInterval
import com.ditchoom.mqtt5.controlpacket.properties.PayloadFormatIndicator
import com.ditchoom.mqtt5.controlpacket.properties.ResponseTopic
import com.ditchoom.mqtt5.controlpacket.properties.SubscriptionIdentifier
import com.ditchoom.mqtt5.controlpacket.properties.TopicAlias
import com.ditchoom.mqtt5.controlpacket.properties.UserProperty
import com.ditchoom.mqtt5.controlpacket.properties.WillDelayInterval
import com.ditchoom.mqtt5.controlpacket.properties.encodeProperty
import com.ditchoom.mqtt5.controlpacket.properties.encodedSize
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
        val expected =
            PublishMessageV5.ofRaw(
                topic = TopicName.fromOrThrow("a"),
                qos = QualityOfService.AT_LEAST_ONCE,
                packetIdentifier = 1,
            )
        val buffer = BufferFactory.Default.allocate(expected.packetSize())
        expected.serialize(buffer)
        buffer.resetForRead()
        assertEquals(0b00110010, buffer.readByte(), "fixed header byte 1")
        assertEquals(6, buffer.readVariableByteInteger(), "fixed header remaining length")
        assertEquals(
            "a",
            buffer.readMqttUtf8StringNotValidatedSized().second.toString(),
            "topic name",
        )
        assertEquals(1u, buffer.readUnsignedShort(), "packet identifier")
        assertEquals(0, buffer.readProperties()?.count() ?: 0, "properties")
        buffer.resetForRead()
        val actual = ControlPacketV5.from(buffer) as PublishMessageV5<*>
        assertEquals(expected, actual)
    }

    @Test
    fun qosBothBitsSetTo1ThrowsMalformedPacketException() {
        val byte1 = 0b00111110.toByte()
        val remainingLength = 1
        val buffer = BufferFactory.Default.allocate(3)
        buffer.writeByte(byte1)
        buffer.writeVariableByteInteger(remainingLength)
        buffer.writeByte(1)
        buffer.resetForRead()
        try {
            ControlPacketV5.from(buffer) as PublishMessageV5<*>
            fail()
        } catch (_: MalformedPacketException) {
        }
    }

    @Test
    fun payloadFormatIndicatorDefault() {
        val expected = PublishMessageV5.ofRaw(topic = TopicName.fromOrThrow("t"))
        val buffer = BufferFactory.Default.allocate(expected.packetSize())
        expected.serialize(buffer)
        buffer.resetForRead()
        assertEquals(0b00110000, buffer.readByte(), "fixed header byte 1")
        assertEquals(4, buffer.readVariableByteInteger(), "fixed header remaining length")
        assertEquals(
            "t",
            buffer.readMqttUtf8StringNotValidatedSized().second.toString(),
            "topic name",
        )
        assertEquals(0, buffer.readProperties()?.count() ?: 0, "properties")
        buffer.resetForRead()
        val publish = ControlPacketV5.from(buffer) as PublishMessageV5<*>
        assertFalse(publish.properties.payloadFormatIndicator)
    }

    @Test
    fun payloadFormatIndicatorTrue() {
        val expected =
            PublishMessageV5.ofRaw(
                topic = TopicName.fromOrThrow("t"),
                properties = PublishMessageV5.Properties(payloadFormatIndicator = true),
            )
        val buffer = BufferFactory.Default.allocate(expected.packetSize())
        expected.serialize(buffer)
        buffer.resetForRead()
        assertEquals(0b00110000, buffer.readByte(), "fixed header byte 1")
        assertEquals(6, buffer.readVariableByteInteger(), "fixed header remaining length")
        assertEquals(
            "t",
            buffer.readMqttUtf8StringNotValidatedSized().second.toString(),
            "topic name",
        )
        val propertiesActual = buffer.readProperties()
        assertEquals(1, propertiesActual?.count() ?: 0, "properties")
        assertEquals(
            true,
            (propertiesActual?.first() as PayloadFormatIndicator).isUtf8,
        )
        buffer.resetForRead()
        val publish = ControlPacketV5.from(buffer) as PublishMessageV5<*>
        assertTrue(publish.properties.payloadFormatIndicator)
    }

    @Test
    fun payloadFormatIndicatorFalse() {
        val expected =
            PublishMessageV5.ofRaw(
                topic = TopicName.fromOrThrow("t"),
                properties = PublishMessageV5.Properties(payloadFormatIndicator = false),
            )
        val buffer = BufferFactory.Default.allocate(expected.packetSize())
        expected.serialize(buffer)
        buffer.resetForRead()
        assertEquals(0b00110000, buffer.readByte(), "fixed header byte 1")
        assertEquals(4, buffer.readVariableByteInteger(), "fixed header remaining length")
        assertEquals(
            "t",
            buffer.readMqttUtf8StringNotValidatedSized().second.toString(),
            "topic name",
        )
        val propertiesActual = buffer.readProperties()
        assertEquals(0, propertiesActual?.count() ?: 0, "properties")
        assertNull((propertiesActual?.firstOrNull() as? PayloadFormatIndicator)?.isUtf8)
        buffer.resetForRead()
        val publish = ControlPacketV5.from(buffer) as PublishMessageV5<*>
        assertFalse(publish.properties.payloadFormatIndicator)
    }

    @Test
    fun payloadFormatIndicatorDuplicateThrowsProtocolError() {
        val obj1 = PayloadFormatIndicator(false)
        val obj2 = obj1
        val buffer = BufferFactory.Default.allocate(5)
        buffer.writeVariableByteInteger(encodedSize(obj1) + encodedSize(obj2))
        encodeProperty(buffer, obj1)
        encodeProperty(buffer, obj2)
        buffer.resetForRead()
        try {
            PublishMessageV5.Properties.from(buffer.readProperties())
            fail()
        } catch (_: ProtocolError) {
        }
    }

    @Test
    fun messageExpiryInterval() {
        val msg =
            PublishMessageV5.ofRaw(
                topic = TopicName.fromOrThrow("t"),
                properties = PublishMessageV5.Properties(messageExpiryInterval = 2),
            )
        val buffer = BufferFactory.Default.allocate(msg.packetSize())
        msg.serialize(buffer)
        buffer.resetForRead()
        assertEquals(0b00110000, buffer.readByte(), "fixed header byte 1")
        assertEquals(9, buffer.readVariableByteInteger(), "fixed header remaining length")
        assertEquals(
            "t",
            buffer.readMqttUtf8StringNotValidatedSized().second.toString(),
            "topic name",
        )
        val propertiesActual = buffer.readProperties()
        assertEquals(1, propertiesActual?.count() ?: 0, "properties")
        assertEquals(
            2u,
            (propertiesActual?.firstOrNull() as? MessageExpiryInterval)?.seconds,
        )
        buffer.resetForRead()
        val publish = ControlPacketV5.from(buffer) as PublishMessageV5<*>
        assertEquals(2L, publish.properties.messageExpiryInterval)
    }

    @Test
    fun messageExpiryIntervalDuplicateThrowsProtocolError() {
        val obj1 = MessageExpiryInterval(2u)
        val obj2 = obj1
        val buffer = BufferFactory.Default.allocate(11)
        buffer.writeVariableByteInteger(encodedSize(obj1) + encodedSize(obj2))
        encodeProperty(buffer, obj1)
        encodeProperty(buffer, obj2)
        buffer.resetForRead()
        try {
            PublishMessageV5.Properties.from(buffer.readProperties())
            fail()
        } catch (_: ProtocolError) {
        }
    }

    @Test
    fun topicAlias() {
        val expected =
            PublishMessageV5.ofRaw(
                topic = TopicName.fromOrThrow("t"),
                properties = PublishMessageV5.Properties(topicAlias = 2),
            )
        val buffer = BufferFactory.Default.allocate(expected.packetSize())
        expected.serialize(buffer)
        buffer.resetForRead()
        assertEquals(0b00110000, buffer.readByte(), "fixed header byte 1")
        assertEquals(7, buffer.readVariableByteInteger(), "fixed header remaining length")
        assertEquals(
            "t",
            buffer.readMqttUtf8StringNotValidatedSized().second.toString(),
            "topic name",
        )
        val propertiesActual = buffer.readProperties()
        assertEquals(1, propertiesActual?.count() ?: 0, "properties")
        assertEquals(2.toUShort(), (propertiesActual?.firstOrNull() as? TopicAlias)?.value)
        buffer.resetForRead()
        val publish = ControlPacketV5.from(buffer) as PublishMessageV5<*>
        assertEquals(expected, publish)
    }

    @Test
    fun topicAliasZeroValueThrowsProtocolError() {
        assertFailsWith<ProtocolError> {
            PublishMessageV5.Properties(topicAlias = 0)
        }
        assertFails {
            PublishMessageV5.ofRaw(
                topic = TopicName.fromOrThrow("t"),
                properties = PublishMessageV5.Properties(topicAlias = 0),
            )
        }
    }

    @Test
    fun topicAliasDuplicateThrowsProtocolError() {
        val obj1 = TopicAlias(2.toUShort())
        val obj2 = obj1
        val buffer = BufferFactory.Default.allocate(7)
        buffer.writeVariableByteInteger(encodedSize(obj1) + encodedSize(obj2))
        encodeProperty(buffer, obj1)
        encodeProperty(buffer, obj2)
        buffer.resetForRead()
        try {
            PublishMessageV5.Properties.from(buffer.readProperties())
            fail()
        } catch (_: ProtocolError) {
        }
    }

    @Test
    fun responseTopic() {
        val actual =
            PublishMessageV5.ofRaw(
                topic = TopicName.fromOrThrow("t"),
                properties = PublishMessageV5.Properties(responseTopic = TopicName.fromOrThrow("t/as")),
            )
        val buffer = BufferFactory.Default.allocate(actual.packetSize())
        actual.serialize(buffer)
        buffer.resetForRead()
        assertEquals(0b00110000, buffer.readByte(), "fixed header byte 1")
        assertEquals(11, buffer.readVariableByteInteger(), "fixed header remaining length")
        assertEquals(
            "t",
            buffer.readMqttUtf8StringNotValidatedSized().second.toString(),
            "topic name",
        )
        assertEquals(7, buffer.readVariableByteInteger(), "property length")
        assertEquals(0x08, buffer.readByte(), "property identifier response topic")
        assertEquals(
            "t/as",
            buffer.readMqttUtf8StringNotValidatedSized().second.toString(),
            "response topic value",
        )
        buffer.resetForRead()
        val publish = ControlPacketV5.from(buffer) as PublishMessageV5<*>
        assertEquals(
            "t/as",
            publish.properties.responseTopic
                ?.toString(),
        )
    }

    @Test
    fun responseTopicDuplicateThrowsProtocolError() {
        val obj1 = ResponseTopic("t/as")
        val obj2 = obj1
        val buffer = BufferFactory.Default.allocate(15)
        buffer.writeVariableByteInteger(encodedSize(obj1) + encodedSize(obj2))
        encodeProperty(buffer, obj1)
        encodeProperty(buffer, obj2)
        buffer.resetForRead()
        try {
            PublishMessageV5.Properties.from(buffer.readProperties())
            fail()
        } catch (_: ProtocolError) {
        }
    }

    private val yoyoBuffer =
        BufferFactory.Default
            .allocate(4)
            .also { it.writeString("yoyo", Charset.UTF8) }

    @Test
    fun correlationData() {
        yoyoBuffer.position(0)
        val actual =
            PublishMessageV5.ofRaw(
                topic = TopicName.fromOrThrow("t"),
                properties = PublishMessageV5.Properties(correlationData = yoyoBuffer),
            )
        val buffer = BufferFactory.Default.allocate(actual.packetSize())
        actual.serialize(buffer)
        buffer.resetForRead()
        assertEquals(0b00110000, buffer.readByte(), "fixed header byte 1")
        assertEquals(11, buffer.readVariableByteInteger(), "fixed header remaining length")
        assertEquals(
            "t",
            buffer.readMqttUtf8StringNotValidatedSized().second.toString(),
            "topic name",
        )
        assertEquals(7, buffer.readVariableByteInteger(), "property length")
        assertEquals(0x09, buffer.readByte(), "property identifier correlation data")
        assertEquals(
            4u,
            buffer.readUnsignedShort(),
            "property binary data size for correlation data",
        )
        assertEquals("yoyo", buffer.readString(4, Charset.UTF8), "correlation data payload")
        buffer.resetForRead()
        val publish = ControlPacketV5.from(buffer) as PublishMessageV5<*>
        assertEquals(
            "yoyo",
            publish.properties.correlationData
                ?.readString(4, Charset.UTF8)
                .toString(),
        )
    }

    @Test
    fun correlationDataDuplicateThrowsProtocolError() {
        yoyoBuffer.position(0)
        val obj1 = CorrelationData(yoyoBuffer.remaining().toUShort(), yoyoBuffer)
        val obj2 = CorrelationData(yoyoBuffer.remaining().toUShort(), yoyoBuffer)
        val buffer = BufferFactory.Default.allocate(15)
        buffer.writeVariableByteInteger(encodedSize(obj1) + encodedSize(obj2))
        encodeProperty(buffer, obj1)
        encodeProperty(buffer, obj2)
        buffer.resetForRead()
        try {
            PublishMessageV5.Properties.from(buffer.readProperties())
            fail()
        } catch (_: ProtocolError) {
        }
    }

    @Test
    fun variableHeaderPropertyUserProperty() {
        val props = PublishMessageV5.Properties.from(setOf(UserProperty("key", "value")))
        val userPropertyResult = props.userProperty
        for ((key, value) in userPropertyResult) {
            assertEquals(key, "key")
            assertEquals(value, "value")
        }
        assertEquals(userPropertyResult.size, 1)

        val request =
            PublishMessageV5.ofRaw(
                topic = TopicName.fromOrThrow("t"),
                properties = props,
            )
        val buffer = BufferFactory.Default.allocate(100)
        request.serialize(buffer)
        buffer.resetForRead()
        val requestRead = ControlPacketV5.from(buffer) as PublishMessageV5<*>
        val (key, value) =
            requestRead.properties.userProperty
                .first()
        assertEquals("key", key.toString())
        assertEquals("value", value.toString())
    }

    @Test
    fun subscriptionIdentifier() {
        val actual =
            PublishMessageV5.ofRaw(
                topic = TopicName.fromOrThrow("t"),
                properties = PublishMessageV5.Properties(subscriptionIdentifier = setOf(2L)),
            )
        val buffer = BufferFactory.Default.allocate(actual.packetSize())
        actual.serialize(buffer)
        buffer.resetForRead()
        val publish = ControlPacketV5.from(buffer) as PublishMessageV5<*>
        assertEquals(
            2L,
            publish.properties.subscriptionIdentifier
                .first(),
        )
    }

    @Test
    fun subscriptionIdentifierZeroThrowsProtocolError() {
        val obj1 = SubscriptionIdentifier(0)
        val buffer = BufferFactory.Default.allocate(6)
        val size = encodedSize(obj1)
        buffer.writeVariableByteInteger(size)
        encodeProperty(buffer, obj1)
        buffer.resetForRead()
        assertFailsWith<ProtocolError> { PublishMessageV5.Properties.from(buffer.readProperties()) }
    }

    @Test
    fun contentType() {
        val actual =
            PublishMessageV5.ofRaw(
                topic = TopicName.fromOrThrow("t"),
                properties = PublishMessageV5.Properties(contentType = "t/as"),
            )
        val buffer = BufferFactory.Default.allocate(actual.packetSize())
        actual.serialize(buffer)
        buffer.resetForRead()
        val publish = ControlPacketV5.from(buffer) as PublishMessageV5<*>
        assertEquals(
            "t/as",
            publish.properties.contentType
                ?.toString(),
        )
    }

    @Test
    fun contentTypeDuplicateThrowsProtocolError() {
        val obj1 = ContentType("t/as")
        val obj2 = obj1
        val buffer = BufferFactory.Default.allocate(15)
        buffer.writeVariableByteInteger(encodedSize(obj1) + encodedSize(obj2))
        encodeProperty(buffer, obj1)
        encodeProperty(buffer, obj2)
        buffer.resetForRead()
        assertFailsWith<ProtocolError> { PublishMessageV5.Properties.from(buffer.readProperties()) }
    }

    @Test
    fun invalidPropertyOnVariableHeaderThrowsMalformedPacketException() {
        val method = WillDelayInterval(3u)
        try {
            PublishMessageV5.Properties.from(listOf(method, method))
            fail()
        } catch (_: MalformedPacketException) {
        }
    }
}
