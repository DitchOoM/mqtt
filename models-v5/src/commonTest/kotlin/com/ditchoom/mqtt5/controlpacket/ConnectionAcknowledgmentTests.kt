package com.ditchoom.mqtt5.controlpacket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.toReadBuffer
import com.ditchoom.mqtt.MalformedPacketException
import com.ditchoom.mqtt.ProtocolError
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.readVariableByteInteger
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.variableByteSize
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.writeVariableByteInteger
import com.ditchoom.mqtt.controlpacket.QualityOfService.AT_LEAST_ONCE
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.SERVER_SHUTTING_DOWN
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.SUCCESS
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.UNSPECIFIED_ERROR
import com.ditchoom.mqtt.controlpacket.format.fixed.get
import com.ditchoom.mqtt5.controlpacket.ConnectionAcknowledgment.VariableHeader
import com.ditchoom.mqtt5.controlpacket.ConnectionAcknowledgment.VariableHeader.Properties
import com.ditchoom.mqtt5.controlpacket.properties.AssignedClientIdentifier
import com.ditchoom.mqtt5.controlpacket.properties.Authentication
import com.ditchoom.mqtt5.controlpacket.properties.AuthenticationData
import com.ditchoom.mqtt5.controlpacket.properties.AuthenticationMethod
import com.ditchoom.mqtt5.controlpacket.properties.MaximumPacketSize
import com.ditchoom.mqtt5.controlpacket.properties.MaximumQos
import com.ditchoom.mqtt5.controlpacket.properties.ReasonString
import com.ditchoom.mqtt5.controlpacket.properties.ReceiveMaximum
import com.ditchoom.mqtt5.controlpacket.properties.ResponseInformation
import com.ditchoom.mqtt5.controlpacket.properties.RetainAvailable
import com.ditchoom.mqtt5.controlpacket.properties.ServerKeepAlive
import com.ditchoom.mqtt5.controlpacket.properties.ServerReference
import com.ditchoom.mqtt5.controlpacket.properties.SessionExpiryInterval
import com.ditchoom.mqtt5.controlpacket.properties.SharedSubscriptionAvailable
import com.ditchoom.mqtt5.controlpacket.properties.SubscriptionIdentifierAvailable
import com.ditchoom.mqtt5.controlpacket.properties.TopicAliasMaximum
import com.ditchoom.mqtt5.controlpacket.properties.UserProperty
import com.ditchoom.mqtt5.controlpacket.properties.WildcardSubscriptionAvailable
import com.ditchoom.mqtt5.controlpacket.properties.WillDelayInterval
import com.ditchoom.mqtt5.controlpacket.properties.encodedSize
import com.ditchoom.mqtt5.controlpacket.properties.encodeProperty
import com.ditchoom.mqtt5.controlpacket.properties.MqttPropertyCodec
import com.ditchoom.mqtt5.controlpacket.properties.readProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class ConnectionAcknowledgmentTests {
    @Test
    fun serializeDefaults() {
        val buffer = BufferFactory.Default.allocate(6)
        val actual = ConnectionAcknowledgment()
        actual.serialize(buffer)
        buffer.resetForRead()
        // fixed header
        assertEquals(0b00100000.toUByte(), buffer.readUnsignedByte(), "byte1 fixed header")
        assertEquals(3, buffer.readVariableByteInteger(), "byte2 fixed header remaining length")
        // variable header
        assertEquals(0, buffer.readByte(), "byte0 variable header session Present Flag")
        assertEquals(
            SUCCESS.byte,
            buffer.readUnsignedByte(),
            "byte1 variable header connect reason code",
        )
        assertEquals(0, buffer.readVariableByteInteger(), "property length")
    }

    @Test
    fun deserializeDefaults() {
        val buffer = BufferFactory.Default.allocate(5)
        // fixed header
        buffer.writeUByte(0b00100000.toUByte())
        buffer.writeVariableByteInteger(3)
        // variable header
        buffer.writeByte(0.toByte())
        buffer.writeUByte(SUCCESS.byte)
        buffer.writeVariableByteInteger(0)
        buffer.resetForRead()
        assertEquals(ConnectionAcknowledgment(), ControlPacketV5.from(buffer))
    }

    @Test
    fun bit0SessionPresentFalseFlags() {
        val model = ConnectionAcknowledgment()
        val buffer = BufferFactory.Default.allocate(model.packetSize())
        model.serialize(buffer)
        buffer.resetForRead()
        // skip fixed header (1 byte control + 1 byte remaining length)
        buffer.readUnsignedByte()
        buffer.readVariableByteInteger()
        // first byte of variable header is connect ack flags
        val sessionPresentBit = buffer.readUnsignedByte().get(0)
        assertFalse(sessionPresentBit)

        buffer.resetForRead()
        val result = ControlPacketV5.from(buffer) as ConnectionAcknowledgment
        assertFalse(result.header.sessionPresent)
    }

    @Test
    fun bit0SessionPresentFlags() {
        val model = ConnectionAcknowledgment(VariableHeader(true))
        val buffer = BufferFactory.Default.allocate(model.packetSize())
        model.serialize(buffer)
        buffer.resetForRead()
        // skip fixed header (1 byte control + 1 byte remaining length)
        buffer.readUnsignedByte()
        buffer.readVariableByteInteger()
        // first byte of variable header is connect ack flags
        val sessionPresentBit = buffer.readUnsignedByte().get(0)
        assertTrue(sessionPresentBit)
    }

    @Test
    fun connectReasonCodeDefaultSuccess() {
        val model = ConnectionAcknowledgment()
        val buffer = BufferFactory.Default.allocate(model.packetSize())
        model.serialize(buffer)
        buffer.resetForRead()
        // skip fixed header (1 byte control + 1 byte remaining length)
        buffer.readUnsignedByte()
        buffer.readVariableByteInteger()
        // first byte of variable header is connect ack flags
        val sessionPresentBit = buffer.readUnsignedByte().get(0)
        assertFalse(sessionPresentBit)

        val buffer2 = BufferFactory.Default.allocate(5)
        model.serialize(buffer2)
        buffer2.resetForRead()
        val result = ControlPacketV5.from(buffer2) as ConnectionAcknowledgment
        assertEquals(result.header.connectReason, SUCCESS)
    }

    @Test
    fun connectReasonCodeDefaultUnspecifiedError() {
        val buffer = BufferFactory.Default.allocate(5)
        val model = ConnectionAcknowledgment(VariableHeader(connectReason = UNSPECIFIED_ERROR))
        model.serialize(buffer)
        buffer.resetForRead()
        val model2 = ControlPacketV5.from(buffer) as ConnectionAcknowledgment
        assertEquals(model, model2)
        assertEquals(UNSPECIFIED_ERROR, model2.header.connectReason)
    }

    @Test
    fun sessionExpiryInterval() {
        val actual = ConnectionAcknowledgment(VariableHeader(properties = Properties(4uL)))
        val buffer = BufferFactory.Default.allocate(actual.packetSize())
        actual.serialize(buffer)
        buffer.resetForRead()
        // fixed header
        assertEquals(0b00100000.toUByte(), buffer.readUnsignedByte(), "byte1 fixed header")
        assertEquals(8, buffer.readVariableByteInteger(), "byte2 fixed header remaining length")
        // variable header
        assertEquals(0, buffer.readByte(), "byte0 variable header session Present Flag")
        assertEquals(
            SUCCESS.byte,
            buffer.readUnsignedByte(),
            "byte1 variable header connect reason code",
        )
        assertEquals(5, buffer.readVariableByteInteger(), "property length")
        assertEquals(0x11, buffer.readByte())
        assertEquals(4u, buffer.readUnsignedInt())
        buffer.resetForRead()
        val expected = ControlPacketV5.from(buffer) as ConnectionAcknowledgment
        assertEquals(4uL, expected.header.properties.sessionExpiryIntervalSeconds)
    }

    @Test
    fun sessionExpiryIntervalMultipleTimesThrowsProtocolError() {
        val obj1 = SessionExpiryInterval(4u)
        val obj2 = obj1
        val size = encodedSize(obj1) + encodedSize(obj2)
        val buffer = BufferFactory.Default.allocate(size + variableByteSize(size))
        buffer.writeVariableByteInteger(size.toInt())
        encodeProperty(buffer, obj1)
        encodeProperty(buffer, obj2)
        buffer.resetForRead()
        try {
            Properties.from(buffer.readProperties())
            fail()
        } catch (e: ProtocolError) {
        }
    }

    @Test
    fun receiveMaximum() {
        val actual =
            ConnectionAcknowledgment(VariableHeader(properties = Properties(receiveMaximum = 4)))
        val buffer = BufferFactory.Default.allocate(8)
        actual.serialize(buffer)
        buffer.resetForRead()
        val expected = ControlPacketV5.from(buffer) as ConnectionAcknowledgment
        assertEquals(expected.header.properties.receiveMaximum, 4)
        assertEquals(expected, actual)
    }

    @Test
    fun receiveMaximumSetToZeroThrowsProtocolError() {
        val actual =
            ConnectionAcknowledgment(VariableHeader(properties = Properties(receiveMaximum = 0)))
        val buffer = BufferFactory.Default.allocate(8)
        actual.serialize(buffer)
        buffer.resetForRead()
        try {
            ControlPacketV5.from(buffer)
            fail()
        } catch (e: ProtocolError) {
        }
    }

    @Test
    fun receiveMaximumMultipleTimesThrowsProtocolError() {
        val obj1 = ReceiveMaximum(4.toUShort())
        val obj2 = obj1
        val buffer = BufferFactory.Default.allocate(7)
        val size = encodedSize(obj1) + encodedSize(obj1)
        buffer.writeVariableByteInteger(size.toInt())
        encodeProperty(buffer, obj1)
        encodeProperty(buffer, obj2)
        buffer.resetForRead()
        try {
            Properties.from(buffer.readProperties())
            fail()
        } catch (e: ProtocolError) {
        }
    }

    @Test
    fun maximumQos() {
        val actual =
            ConnectionAcknowledgment(VariableHeader(properties = Properties(maximumQos = AT_LEAST_ONCE)))
        val buffer = BufferFactory.Default.allocate(8)
        actual.serialize(buffer)
        buffer.resetForRead()
        val expected = ControlPacketV5.from(buffer) as ConnectionAcknowledgment
        assertEquals(expected.header.properties.maximumQos, AT_LEAST_ONCE)
        assertEquals(expected, actual)
    }

    @Test
    fun maximumQosMultipleTimesThrowsProtocolError() {
        val obj1 = MaximumQos(true)
        val obj2 = obj1
        val size = encodedSize(obj1) + encodedSize(obj2)
        val buffer1 = BufferFactory.Default.allocate(size + variableByteSize(size))
        buffer1.writeVariableByteInteger(size)
        encodeProperty(buffer1, obj1)
        encodeProperty(buffer1, obj2)
        buffer1.resetForRead()
        try {
            VariableHeader(properties = Properties.from(buffer1.readProperties()))
            fail()
        } catch (e: ProtocolError) {
        }
    }

    @Test
    fun retainAvailableTrue() {
        val actual =
            ConnectionAcknowledgment(VariableHeader(properties = Properties(retainAvailable = true)))
        val buffer = BufferFactory.Default.allocate(5)
        actual.serialize(buffer)
        buffer.resetForRead()
        val expected = ControlPacketV5.from(buffer) as ConnectionAcknowledgment
        assertEquals(expected.header.properties.retainAvailable, true)
        assertEquals(expected, actual)
    }

    @Test
    fun retainAvailableFalse() {
        val actual =
            ConnectionAcknowledgment(VariableHeader(properties = Properties(retainAvailable = false)))
        val buffer = BufferFactory.Default.allocate(7)
        actual.serialize(buffer)
        buffer.resetForRead()
        val expected = ControlPacketV5.from(buffer) as ConnectionAcknowledgment
        assertEquals(expected.header.properties.retainAvailable, false)
        assertEquals(expected, actual)
    }

    @Test
    fun retainAvailableSendDefaults() {
        val actual = ConnectionAcknowledgment()
        val buffer = BufferFactory.Default.allocate(5)
        actual.serialize(buffer)
        buffer.resetForRead()
        val expected = ControlPacketV5.from(buffer) as ConnectionAcknowledgment
        assertEquals(expected.header.properties.retainAvailable, true)
        assertEquals(expected, actual)
    }

    @Test
    fun retainAvailableMultipleTimesThrowsProtocolError() {
        val obj1 = RetainAvailable(true)
        val obj2 = obj1
        val buffer = BufferFactory.Default.allocate(5)
        val size = encodedSize(obj1) + encodedSize(obj2)
        buffer.writeVariableByteInteger(size.toInt())
        encodeProperty(buffer, obj1)
        encodeProperty(buffer, obj2)
        buffer.resetForRead()
        try {
            Properties.from(buffer.readProperties())
            fail()
        } catch (e: ProtocolError) {
        }
    }

    @Test
    fun maximumPacketSize() {
        val actual =
            ConnectionAcknowledgment(VariableHeader(properties = Properties(maximumPacketSize = 4u)))
        val buffer = BufferFactory.Default.allocate(actual.packetSize())
        actual.serialize(buffer)
        buffer.resetForRead()
        val expected = ControlPacketV5.from(buffer) as ConnectionAcknowledgment
        assertEquals(expected.header.properties.maximumPacketSize, 4u)
        assertEquals(expected, actual)
    }

    @Test
    fun maximumPacketSizeSetToZeroThrowsProtocolError() {
        val actual =
            ConnectionAcknowledgment(VariableHeader(properties = Properties(maximumPacketSize = 0u)))
        val buffer = BufferFactory.Default.allocate(actual.packetSize())
        actual.serialize(buffer)
        buffer.resetForRead()
        try {
            ControlPacketV5.from(buffer)
            fail()
        } catch (e: ProtocolError) {
        }
    }

    @Test
    fun maximumPacketSizeMultipleTimesThrowsProtocolError() {
        val obj1 = MaximumPacketSize(4u)
        val obj2 = obj1
        val size = encodedSize(obj1) + encodedSize(obj2)
        val buffer = BufferFactory.Default.allocate(size + variableByteSize(size))
        buffer.writeVariableByteInteger(size)
        encodeProperty(buffer, obj1)
        encodeProperty(buffer, obj2)
        buffer.resetForRead()
        try {
            Properties.from(buffer.readProperties())
            fail()
        } catch (e: ProtocolError) {
        }
    }

    @Test
    fun assignedClientIdentifier() {
        val actual =
            ConnectionAcknowledgment(
                VariableHeader(properties = Properties(assignedClientIdentifier = "yolo")),
            )
        val buffer = BufferFactory.Default.allocate(12)
        actual.serialize(buffer)
        buffer.resetForRead()
        val expected = ControlPacketV5.from(buffer) as ConnectionAcknowledgment
        assertEquals(
            expected.header.properties.assignedClientIdentifier
                .toString(),
            "yolo",
        )
        assertEquals(expected.toString(), actual.toString())
    }

    @Test
    fun assignedClientIdentifierMultipleTimesThrowsProtocolError() {
        val obj1 = AssignedClientIdentifier("yolo")
        val obj2 = obj1
        val buffer = BufferFactory.Default.allocate(15)
        val size = encodedSize(obj1) + encodedSize(obj2)
        buffer.writeVariableByteInteger(size.toInt())
        encodeProperty(buffer, obj1)
        encodeProperty(buffer, obj2)
        buffer.resetForRead()
        try {
            Properties.from(buffer.readProperties())
            fail()
        } catch (e: ProtocolError) {
        }
    }

    @Test
    fun topicAliasMaximum() {
        val actual =
            ConnectionAcknowledgment(
                VariableHeader(properties = Properties(topicAliasMaximum = 4)),
            )
        val buffer = BufferFactory.Default.allocate(actual.packetSize())
        actual.serialize(buffer)
        buffer.resetForRead()
        val expected = ControlPacketV5.from(buffer) as ConnectionAcknowledgment
        assertEquals(expected.header.properties.topicAliasMaximum, 4)
    }

    @Test
    fun topicAliasMaximumMultipleTimesThrowsProtocolError() {
        val obj1 = TopicAliasMaximum(4.toUShort())
        val obj2 = obj1
        val buffer = BufferFactory.Default.allocate(7)
        val size = encodedSize(obj1) + encodedSize(obj2)
        buffer.writeVariableByteInteger(size.toInt())
        encodeProperty(buffer, obj1)
        encodeProperty(buffer, obj2)
        buffer.resetForRead()
        try {
            Properties.from(buffer.readProperties())
            fail()
        } catch (e: ProtocolError) {
        }
    }

    @Test
    fun reasonString() {
        val actual =
            ConnectionAcknowledgment(
                VariableHeader(properties = Properties(reasonString = "yolo")),
            )
        val buffer = BufferFactory.Default.allocate(12)
        actual.serialize(buffer)
        buffer.resetForRead()
        val expected = ControlPacketV5.from(buffer) as ConnectionAcknowledgment
        assertEquals(
            expected.header.properties.reasonString
                .toString(),
            "yolo",
        )
        assertEquals(expected.toString(), actual.toString())
    }

    @Test
    fun reasonStringMultipleTimesThrowsProtocolError() {
        val obj1 = ReasonString("yolo")
        val obj2 = obj1
        val buffer = BufferFactory.Default.allocate(15)
        val size = encodedSize(obj1) + encodedSize(obj2)
        buffer.writeVariableByteInteger(size.toInt())
        encodeProperty(buffer, obj1)
        encodeProperty(buffer, obj2)
        buffer.resetForRead()
        try {
            Properties.from(buffer.readProperties())
            fail()
        } catch (e: ProtocolError) {
        }
    }

    @Test
    fun variableHeaderPropertyUserProperty() {
        val props = Properties.from(setOf(UserProperty("key", "value")))
        val userPropertyResult = props.userProperty
        for ((key, value) in userPropertyResult) {
            assertEquals(key, "key")
            assertEquals(value, "value")
        }
        assertEquals(userPropertyResult.size, 1)

        val connack = ConnectionAcknowledgment(VariableHeader(properties = props))
        val buffer = BufferFactory.Default.allocate(18)
        connack.serialize(buffer)
        buffer.resetForRead()
        val requestRead = ControlPacketV5.from(buffer) as ConnectionAcknowledgment
        val (key, value) =
            requestRead.header.properties.userProperty
                .first()
        assertEquals(key.toString(), "key")
        assertEquals(value.toString(), "value")
    }

    @Test
    fun wildcardSubscriptionAvailable() {
        val actual =
            ConnectionAcknowledgment(
                VariableHeader(
                    properties =
                        Properties(
                            supportsWildcardSubscriptions = false,
                        ),
                ),
            )
        val buffer = BufferFactory.Default.allocate(7)
        actual.serialize(buffer)
        buffer.resetForRead()
        val expected = ControlPacketV5.from(buffer) as ConnectionAcknowledgment
        assertEquals(expected.header.properties.supportsWildcardSubscriptions, false)
    }

    @Test
    fun wildcardSubscriptionAvailableDefaults() {
        val actual = ConnectionAcknowledgment()
        val buffer = BufferFactory.Default.allocate(5)
        actual.serialize(buffer)
        buffer.resetForRead()
        val expected = ControlPacketV5.from(buffer) as ConnectionAcknowledgment
        assertEquals(expected.header.properties.supportsWildcardSubscriptions, true)
    }

    @Test
    fun wildcardSubscriptionAvailableMultipleTimesThrowsProtocolError() {
        val obj1 = WildcardSubscriptionAvailable(true)
        val obj2 = obj1
        val buffer = BufferFactory.Default.allocate(5)
        val size = encodedSize(obj1) + encodedSize(obj2)
        buffer.writeVariableByteInteger(size.toInt())
        encodeProperty(buffer, obj1)
        encodeProperty(buffer, obj2)
        buffer.resetForRead()
        try {
            Properties.from(buffer.readProperties())
            fail()
        } catch (e: ProtocolError) {
        }
    }

    @Test
    fun subscriptionIdentifierAvailableDefaults() {
        val actual = ConnectionAcknowledgment()
        val buffer = BufferFactory.Default.allocate(5)
        actual.serialize(buffer)
        buffer.resetForRead()
        val expected = ControlPacketV5.from(buffer) as ConnectionAcknowledgment
        assertEquals(expected.header.properties.subscriptionIdentifiersAvailable, true)
    }

    @Test
    fun subscriptionIdentifierAvailable() {
        val actual =
            ConnectionAcknowledgment(
                VariableHeader(
                    properties =
                        Properties(
                            subscriptionIdentifiersAvailable = false,
                        ),
                ),
            )
        val buffer = BufferFactory.Default.allocate(7)
        actual.serialize(buffer)
        buffer.resetForRead()
        val expected = ControlPacketV5.from(buffer) as ConnectionAcknowledgment
        assertEquals(expected.header.properties.subscriptionIdentifiersAvailable, false)
    }

    @Test
    fun subscriptionIdentifierAvailableMultipleTimesThrowsProtocolError() {
        val obj1 = SubscriptionIdentifierAvailable(true)
        val obj2 = obj1
        val buffer = BufferFactory.Default.allocate(5)
        val size = encodedSize(obj1) + encodedSize(obj2)
        buffer.writeVariableByteInteger(size.toInt())
        encodeProperty(buffer, obj1)
        encodeProperty(buffer, obj2)
        buffer.resetForRead()
        try {
            Properties.from(buffer.readProperties())
            fail()
        } catch (e: ProtocolError) {
        }
    }

    @Test
    fun sharedSubscriptionAvailableDefaults() {
        val actual = ConnectionAcknowledgment()
        val buffer = BufferFactory.Default.allocate(5)
        actual.serialize(buffer)
        buffer.resetForRead()
        val expected = ControlPacketV5.from(buffer) as ConnectionAcknowledgment
        assertEquals(expected.header.properties.sharedSubscriptionAvailable, true)
    }

    @Test
    fun sharedSubscriptionAvailable() {
        val actual =
            ConnectionAcknowledgment(
                VariableHeader(
                    properties =
                        Properties(
                            sharedSubscriptionAvailable = false,
                        ),
                ),
            )
        val buffer = BufferFactory.Default.allocate(7)
        actual.serialize(buffer)
        buffer.resetForRead()
        val expected = ControlPacketV5.from(buffer) as ConnectionAcknowledgment
        assertEquals(expected.header.properties.sharedSubscriptionAvailable, false)
    }

    @Test
    fun sharedSubscriptionAvailableMultipleTimesThrowsProtocolError() {
        val obj1 = SharedSubscriptionAvailable(true)
        val obj2 = obj1
        val buffer = BufferFactory.Default.allocate(5)
        val size = encodedSize(obj1) + encodedSize(obj2)
        buffer.writeVariableByteInteger(size.toInt())
        encodeProperty(buffer, obj1)
        encodeProperty(buffer, obj2)
        buffer.resetForRead()
        try {
            Properties.from(buffer.readProperties())
            fail()
        } catch (e: ProtocolError) {
        }
    }

    @Test
    fun serverKeepAlive() {
        val actual =
            ConnectionAcknowledgment(VariableHeader(properties = Properties(serverKeepAlive = 5)))
        val buffer = BufferFactory.Default.allocate(8)
        actual.serialize(buffer)
        buffer.resetForRead()
        val expected = ControlPacketV5.from(buffer) as ConnectionAcknowledgment
        assertEquals(expected.header.properties.sharedSubscriptionAvailable, true)
    }

    @Test
    fun serverKeepAliveMultipleTimesThrowsProtocolError() {
        val obj1 = ServerKeepAlive(5.toUShort())
        val obj2 = obj1
        val buffer = BufferFactory.Default.allocate(7)
        val size = encodedSize(obj1) + encodedSize(obj2)
        buffer.writeVariableByteInteger(size.toInt())
        encodeProperty(buffer, obj1)
        encodeProperty(buffer, obj2)
        buffer.resetForRead()
        try {
            Properties.from(buffer.readProperties())
            fail()
        } catch (e: ProtocolError) {
        }
    }

    @Test
    fun responseInformation() {
        val actual =
            ConnectionAcknowledgment(
                VariableHeader(properties = Properties(responseInformation = "yolo")),
            )
        val buffer = BufferFactory.Default.allocate(12)
        actual.serialize(buffer)
        buffer.resetForRead()
        val expected = ControlPacketV5.from(buffer) as ConnectionAcknowledgment
        assertEquals(
            expected.header.properties.responseInformation
                .toString(),
            "yolo",
        )
    }

    @Test
    fun responseInformationMultipleTimesThrowsProtocolError() {
        val obj1 = ResponseInformation("yolo")
        val obj2 = obj1
        val buffer = BufferFactory.Default.allocate(15)
        val size = encodedSize(obj1) + encodedSize(obj2)
        buffer.writeVariableByteInteger(size.toInt())
        encodeProperty(buffer, obj1)
        encodeProperty(buffer, obj2)
        buffer.resetForRead()
        try {
            Properties.from(buffer.readProperties())
            fail()
        } catch (e: ProtocolError) {
        }
    }

    @Test
    fun serverReference() {
        val actual =
            ConnectionAcknowledgment(
                VariableHeader(properties = Properties(serverReference = "yolo")),
            )
        val buffer = BufferFactory.Default.allocate(12)
        actual.serialize(buffer)
        buffer.resetForRead()
        val expected = ControlPacketV5.from(buffer) as ConnectionAcknowledgment
        assertEquals(
            expected.header.properties.serverReference
                .toString(),
            "yolo",
        )
    }

    @Test
    fun serverReferenceMultipleTimesThrowsProtocolError() {
        val obj1 = ServerReference("yolo")
        val obj2 = obj1
        val buffer = BufferFactory.Default.allocate(15)
        val size = encodedSize(obj1) + encodedSize(obj2)
        buffer.writeVariableByteInteger(size.toInt())
        encodeProperty(buffer, obj1)
        encodeProperty(buffer, obj2)
        buffer.resetForRead()
        try {
            Properties.from(buffer.readProperties())
            fail()
        } catch (e: ProtocolError) {
        }
    }

    private val buffer1234 =
        BufferFactory.Default
            .allocate(4)
            .also { it.write("1234".toReadBuffer(Charset.UTF8)) }

    @Test
    fun authenticationMethodAndData() {
        val actual =
            ConnectionAcknowledgment(
                VariableHeader(
                    properties =
                        Properties(
                            authentication =
                                Authentication("yolo", buffer1234),
                        ),
                ),
            )
        val buffer = BufferFactory.Default.allocate(19)
        actual.serialize(buffer)
        buffer.resetForRead()
        val expected = ControlPacketV5.from(buffer) as ConnectionAcknowledgment
        assertEquals(
            expected.header.properties.authentication
                ?.method
                ?.toString(),
            "yolo",
        )
        val decodedData = expected.header.properties.authentication?.data as? ReadBuffer
        assertNotNull(decodedData)
        decodedData.position(0)
        buffer1234.position(0)
        assertEquals(decodedData.remaining(), buffer1234.remaining(), "auth data length mismatch")
    }

    @Test
    fun authenticationMethodMultipleTimesThrowsProtocolError() {
        val obj1 = AuthenticationMethod("yolo")
        val obj2 = obj1
        val buffer = BufferFactory.Default.allocate(15)
        val size = encodedSize(obj1) + encodedSize(obj2)
        buffer.writeVariableByteInteger(size.toInt())
        encodeProperty(buffer, obj1)
        encodeProperty(buffer, obj2)
        buffer.resetForRead()
        try {
            Properties.from(buffer.readProperties())
            fail()
        } catch (e: ProtocolError) {
        }
    }

    @Test
    fun authenticationDataMultipleTimesThrowsProtocolError() {
        buffer1234.position(0)
        val obj1 = AuthenticationData(buffer1234.remaining().toUShort(), buffer1234)
        val obj2 = obj1
        val buffer = BufferFactory.Default.allocate(15)
        val size = encodedSize(obj1) + encodedSize(obj2)
        buffer.writeVariableByteInteger(size.toInt())
        encodeProperty(buffer, obj1)
        encodeProperty(buffer, obj2)
        buffer.resetForRead()
        try {
            Properties.from(buffer.readProperties())
            fail()
        } catch (e: ProtocolError) {
        }
    }

    @Test
    fun invalidPropertyOnVariableHeaderThrowsMalformedPacketException() {
        val method = WillDelayInterval(3u)
        try {
            Properties.from(listOf(method, method))
            fail()
        } catch (e: MalformedPacketException) {
        }
    }

    @Test
    fun connectionReasonByteOnVariableHeaderIsInvalidThrowsMalformedPacketException() {
        val buffer = BufferFactory.Default.allocate(2)
        buffer.writeByte(1.toByte())
        buffer.writeUByte(SERVER_SHUTTING_DOWN.byte)
        buffer.resetForRead()
        try {
            VariableHeader.from(buffer, 2)
            fail()
        } catch (e: MalformedPacketException) {
        }
    }
}
