package com.ditchoom.mqtt5.controlpacket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.mqtt.MalformedPacketException
import com.ditchoom.mqtt.ProtocolError
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.writeVariableByteInteger
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.ADMINISTRATIVE_ACTION
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.BANNED
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.CONNECTION_RATE_EXCEEDED
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.DISCONNECT_WITH_WILL_MESSAGE
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.IMPLEMENTATION_SPECIFIC_ERROR
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.KEEP_ALIVE_TIMEOUT
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.MALFORMED_PACKET
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.MAXIMUM_CONNECTION_TIME
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.MESSAGE_RATE_TOO_HIGH
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.NORMAL_DISCONNECTION
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.NOT_AUTHORIZED
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.PACKET_TOO_LARGE
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.PAYLOAD_FORMAT_INVALID
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.PROTOCOL_ERROR
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.QOS_NOT_SUPPORTED
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.QUOTA_EXCEEDED
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.RECEIVE_MAXIMUM_EXCEEDED
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.RETAIN_NOT_SUPPORTED
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.SERVER_BUSY
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.SERVER_MOVED
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.SERVER_SHUTTING_DOWN
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.SESSION_TAKE_OVER
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.SHARED_SUBSCRIPTIONS_NOT_SUPPORTED
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.SUBSCRIPTION_IDENTIFIERS_NOT_SUPPORTED
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.TOPIC_ALIAS_INVALID
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.TOPIC_FILTER_INVALID
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.TOPIC_NAME_INVALID
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.UNSPECIFIED_ERROR
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.USE_ANOTHER_SERVER
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.WILDCARD_SUBSCRIPTIONS_NOT_SUPPORTED
import com.ditchoom.mqtt5.controlpacket.DisconnectNotification.VariableHeader
import com.ditchoom.mqtt5.controlpacket.DisconnectNotification.VariableHeader.Properties
import com.ditchoom.mqtt5.controlpacket.properties.ReasonString
import com.ditchoom.mqtt5.controlpacket.properties.ServerReference
import com.ditchoom.mqtt5.controlpacket.properties.SessionExpiryInterval
import com.ditchoom.mqtt5.controlpacket.properties.UserProperty
import com.ditchoom.mqtt5.controlpacket.properties.MaximumPacketSize
import com.ditchoom.mqtt5.controlpacket.properties.MqttProperty
import com.ditchoom.mqtt5.controlpacket.properties.WillDelayInterval
import com.ditchoom.mqtt5.controlpacket.properties.encodedSize
import com.ditchoom.mqtt5.controlpacket.properties.encodeProperty
import com.ditchoom.mqtt5.controlpacket.properties.MqttPropertyCodec
import com.ditchoom.mqtt5.controlpacket.properties.readProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.fail

class DisconnectTests {
    /**
     * Regression: SessionExpiryInterval (0x11) is FOUR_BYTE_INTEGER but was calling
     * size(ULong) returning 9 instead of size(UInt) returning 5.
     */
    @Test
    fun sessionExpiryIntervalPropertySize() {
        val prop = SessionExpiryInterval(300u)
        assertEquals(5, encodedSize(prop), "SessionExpiryInterval must be 5 bytes (1 id + 4 value)")
        val buffer = BufferFactory.Default.allocate(5)
        encodeProperty(buffer, prop)
        buffer.resetForRead()
        assertEquals(5, buffer.remaining(), "SessionExpiryInterval must write exactly 5 bytes")
    }

    /**
     * Regression: MaximumPacketSize (0x27) is FOUR_BYTE_INTEGER but was calling
     * size(ULong) returning 9 instead of size(UInt) returning 5.
     */
    @Test
    fun maximumPacketSizePropertySize() {
        val prop = MaximumPacketSize(65536u)
        assertEquals(5, encodedSize(prop), "MaximumPacketSize must be 5 bytes (1 id + 4 value)")
        val buffer = BufferFactory.Default.allocate(5)
        encodeProperty(buffer, prop)
        buffer.resetForRead()
        assertEquals(5, buffer.remaining(), "MaximumPacketSize must write exactly 5 bytes")
    }

    @Test
    fun sessionExpiryInterval() {
        val expected = DisconnectNotification(VariableHeader(properties = Properties(4u)))
        val buffer = BufferFactory.Default.allocate(expected.packetSize())
        expected.serialize(buffer)
        buffer.resetForRead()
        val actual = ControlPacketV5.from(buffer) as DisconnectNotification
        assertEquals(4u, actual.variable.properties.sessionExpiryIntervalSeconds)
        assertEquals(expected, actual)
    }

    @Test
    fun sessionExpiryIntervalMultipleTimesThrowsProtocolError() {
        val obj1 = SessionExpiryInterval(4u)
        val obj2 = obj1
        val size = encodedSize(obj1) + encodedSize(obj2)
        val buffer = BufferFactory.Default.allocate(size + ControlPacket.variableByteSize(size))
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
    fun reasonString() {
        val props = Properties(reasonString = "yolo")
        val header = VariableHeader(NORMAL_DISCONNECTION, properties = props)
        val expected = DisconnectNotification(header)
        val buffer = BufferFactory.Default.allocate(11)
        expected.serialize(buffer)
        buffer.resetForRead()
//        val actual = ControlPacketV5.from(buffer) as DisconnectNotification
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
        val buffer = BufferFactory.Default.allocate(15)
        buffer.writeVariableByteInteger(encodedSize(obj1) + encodedSize(obj2))
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
        val props =
            Properties.from(
                setOf(
                    UserProperty("key", "value"),
                    UserProperty("key", "value"),
                ),
            )
        val userPropertyResult = props.userProperty
        for ((key, value) in userPropertyResult) {
            assertEquals(key, "key")
            assertEquals(value, "value")
        }
        assertEquals(userPropertyResult.size, 1)

        val request =
            DisconnectNotification(VariableHeader(NORMAL_DISCONNECTION, properties = props))
        val buffer = BufferFactory.Default.allocate(17)
        request.serialize(buffer)
        buffer.resetForRead()
        val requestRead = ControlPacketV5.from(buffer) as DisconnectNotification

        val (key, value) =
            requestRead.variable.properties.userProperty
                .first()
        assertEquals(key.toString(), "key")
        assertEquals(value.toString(), "value")
    }

    @Test
    fun invalidReasonCode() {
        assertFailsWith<MalformedPacketException> {
            VariableHeader(BANNED)
        }
    }

    @Test
    fun serverReference() {
        val expected =
            DisconnectNotification(
                VariableHeader(properties = Properties(serverReference = "yolo")),
            )
        val buffer = BufferFactory.Default.allocate(11)
        expected.serialize(buffer)
        buffer.resetForRead()
        val actual = ControlPacketV5.from(buffer) as DisconnectNotification
        assertEquals(expected.toString(), actual.toString())
        assertEquals(expected.variable.properties.serverReference, "yolo")
    }

    @Test
    fun serverReferenceMultipleTimesThrowsProtocolError() {
        val obj1 = ServerReference("yolo")
        val obj2 = obj1
        val buffer = BufferFactory.Default.allocate(15)
        buffer.writeVariableByteInteger(encodedSize(obj1) + encodedSize(obj2))
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
    fun invalidPropertyThrowsException() {
        val pairs = setOf<MqttProperty>(WillDelayInterval(3u))
        try {
            Properties.from(pairs)
            fail()
        } catch (e: MalformedPacketException) {
        }
    }

    @Test
    fun serializeDeserializeDefaults() {
        val disconnect = DisconnectNotification()
        val buffer = BufferFactory.Default.allocate(4)
        disconnect.serialize(buffer)
        buffer.resetForRead()
        val actual = ControlPacketV5.from(buffer) as DisconnectNotification
        assertEquals(disconnect.variable.reasonCode, NORMAL_DISCONNECTION)
        assertEquals(disconnect, actual)
    }

    @Test
    fun serializeDeserializeNormalDisconnection() {
        val disconnect = DisconnectNotification(VariableHeader(NORMAL_DISCONNECTION))
        val buffer = BufferFactory.Default.allocate(4)
        disconnect.serialize(buffer)

        buffer.resetForRead()
        val actual = ControlPacketV5.from(buffer) as DisconnectNotification
        assertEquals(actual.variable.reasonCode, NORMAL_DISCONNECTION)
        assertEquals(disconnect, actual)
    }

    @Test
    fun serializeDeserializeDisconnectWithWillMessage() {
        val reason = DISCONNECT_WITH_WILL_MESSAGE
        val disconnect = DisconnectNotification(VariableHeader(reason))
        val buffer = BufferFactory.Default.allocate(4)
        disconnect.serialize(buffer)

        buffer.resetForRead()
        val actual = ControlPacketV5.from(buffer) as DisconnectNotification
        assertEquals(actual.variable.reasonCode, reason)
        assertEquals(disconnect, actual)
    }

    @Test
    fun serializeDeserializeUnspecifiedError() {
        val reason = UNSPECIFIED_ERROR
        val disconnect = DisconnectNotification(VariableHeader(reason))
        val buffer = BufferFactory.Default.allocate(4)
        disconnect.serialize(buffer)

        buffer.resetForRead()
        val actual = ControlPacketV5.from(buffer) as DisconnectNotification
        assertEquals(disconnect, actual)
        assertEquals(disconnect.variable.reasonCode, reason)
    }

    @Test
    fun serializeDeserializeMalformedPacket() {
        val reason = MALFORMED_PACKET
        val disconnect = DisconnectNotification(VariableHeader(reason))
        val buffer = BufferFactory.Default.allocate(4)
        disconnect.serialize(buffer)

        buffer.resetForRead()
        val actual = ControlPacketV5.from(buffer) as DisconnectNotification
        assertEquals(actual.variable.reasonCode, reason)
        assertEquals(disconnect, actual)
    }

    @Test
    fun serializeDeserializeProtocolError() {
        val reason = PROTOCOL_ERROR
        val disconnect = DisconnectNotification(VariableHeader(reason))
        val buffer = BufferFactory.Default.allocate(4)
        disconnect.serialize(buffer)

        buffer.resetForRead()
        val deserialized = ControlPacketV5.from(buffer) as DisconnectNotification
        assertEquals(deserialized.variable.reasonCode, reason)
        assertEquals(disconnect, deserialized)
    }

    @Test
    fun serializeDeserializeImplementationSpecificError() {
        val reason = IMPLEMENTATION_SPECIFIC_ERROR
        val disconnect = DisconnectNotification(VariableHeader(IMPLEMENTATION_SPECIFIC_ERROR))
        val buffer = BufferFactory.Default.allocate(4)
        disconnect.serialize(buffer)

        buffer.resetForRead()
        val deserialized = ControlPacketV5.from(buffer) as DisconnectNotification
        assertEquals(deserialized.variable.reasonCode, reason)
        assertEquals(disconnect, deserialized)
    }

    @Test
    fun serializeDeserializeNotAuthorized() {
        val reason = NOT_AUTHORIZED
        val disconnect = DisconnectNotification(VariableHeader(reason))
        val buffer = BufferFactory.Default.allocate(4)
        disconnect.serialize(buffer)

        buffer.resetForRead()
        val deserialized = ControlPacketV5.from(buffer) as DisconnectNotification
        assertEquals(deserialized.variable.reasonCode, reason)
        assertEquals(disconnect, deserialized)
    }

    @Test
    fun serializeDeserializeServerBusy() {
        val reason = SERVER_BUSY
        val disconnect = DisconnectNotification(VariableHeader(reason))
        val buffer = BufferFactory.Default.allocate(4)
        disconnect.serialize(buffer)

        buffer.resetForRead()
        val deserialized = ControlPacketV5.from(buffer) as DisconnectNotification
        assertEquals(deserialized.variable.reasonCode, reason)
        assertEquals(disconnect, deserialized)
    }

    @Test
    fun serializeDeserializeServerShuttingDown() {
        val reason = SERVER_SHUTTING_DOWN
        val disconnect = DisconnectNotification(VariableHeader(reason))
        val buffer = BufferFactory.Default.allocate(4)
        disconnect.serialize(buffer)

        buffer.resetForRead()
        val deserialized = ControlPacketV5.from(buffer) as DisconnectNotification
        assertEquals(deserialized.variable.reasonCode, reason)
        assertEquals(disconnect, deserialized)
    }

    @Test
    fun serializeDeserializeKeepAliveTimeout() {
        val reason = KEEP_ALIVE_TIMEOUT
        val disconnect = DisconnectNotification(VariableHeader(reason))
        val buffer = BufferFactory.Default.allocate(4)
        disconnect.serialize(buffer)

        buffer.resetForRead()
        val deserialized = ControlPacketV5.from(buffer) as DisconnectNotification
        assertEquals(deserialized.variable.reasonCode, reason)
        assertEquals(disconnect, deserialized)
    }

    @Test
    fun serializeDeserializeSessionTakeOver() {
        val reason = SESSION_TAKE_OVER
        val disconnect = DisconnectNotification(VariableHeader(reason))
        val buffer = BufferFactory.Default.allocate(4)
        disconnect.serialize(buffer)

        buffer.resetForRead()
        val deserialized = ControlPacketV5.from(buffer) as DisconnectNotification
        assertEquals(deserialized.variable.reasonCode, reason)
        assertEquals(disconnect, deserialized)
    }

    @Test
    fun serializeDeserializeTopicFilterInvalid() {
        val reason = TOPIC_FILTER_INVALID
        val disconnect = DisconnectNotification(VariableHeader(reason))
        val buffer = BufferFactory.Default.allocate(4)
        disconnect.serialize(buffer)

        buffer.resetForRead()
        val deserialized = ControlPacketV5.from(buffer) as DisconnectNotification
        assertEquals(deserialized.variable.reasonCode, reason)
        assertEquals(disconnect, deserialized)
    }

    @Test
    fun serializeDeserializeTopicNameInvalid() {
        val reason = TOPIC_NAME_INVALID
        val disconnect = DisconnectNotification(VariableHeader(reason))
        val buffer = BufferFactory.Default.allocate(4)
        disconnect.serialize(buffer)

        buffer.resetForRead()
        val deserialized = ControlPacketV5.from(buffer) as DisconnectNotification
        assertEquals(deserialized.variable.reasonCode, reason)
        assertEquals(disconnect, deserialized)
    }

    @Test
    fun serializeDeserializeReceiveMaximumExceeded() {
        val reason = RECEIVE_MAXIMUM_EXCEEDED
        val disconnect = DisconnectNotification(VariableHeader(reason))
        val buffer = BufferFactory.Default.allocate(4)
        disconnect.serialize(buffer)

        buffer.resetForRead()
        val deserialized = ControlPacketV5.from(buffer) as DisconnectNotification
        assertEquals(deserialized.variable.reasonCode, reason)
        assertEquals(disconnect, deserialized)
    }

    @Test
    fun serializeDeserializeTopicAliasInvalid() {
        val reason = TOPIC_ALIAS_INVALID
        val disconnect = DisconnectNotification(VariableHeader(reason))
        val buffer = BufferFactory.Default.allocate(4)
        disconnect.serialize(buffer)

        buffer.resetForRead()
        val deserialized = ControlPacketV5.from(buffer) as DisconnectNotification
        assertEquals(deserialized.variable.reasonCode, reason)
        assertEquals(disconnect, deserialized)
    }

    @Test
    fun serializeDeserializePacketTooLarge() {
        val reason = PACKET_TOO_LARGE
        val disconnect = DisconnectNotification(VariableHeader(reason))
        val buffer = BufferFactory.Default.allocate(4)
        disconnect.serialize(buffer)

        buffer.resetForRead()
        val deserialized = ControlPacketV5.from(buffer) as DisconnectNotification
        assertEquals(deserialized.variable.reasonCode, reason)
        assertEquals(disconnect, deserialized)
    }

    @Test
    fun serializeDeserializeMessageRateTooHigh() {
        val reason = MESSAGE_RATE_TOO_HIGH
        val disconnect = DisconnectNotification(VariableHeader(reason))
        val buffer = BufferFactory.Default.allocate(disconnect.packetSize())
        disconnect.serialize(buffer)

        buffer.resetForRead()
        val deserialized = ControlPacketV5.from(buffer) as DisconnectNotification
        assertEquals(deserialized.variable.reasonCode, reason)
        assertEquals(disconnect, deserialized)
    }

    @Test
    fun serializeDeserializeQuotaExceeded() {
        val reason = QUOTA_EXCEEDED
        val disconnect = DisconnectNotification(VariableHeader(reason))
        val buffer = BufferFactory.Default.allocate(4)
        disconnect.serialize(buffer)

        buffer.resetForRead()
        val deserialized = ControlPacketV5.from(buffer) as DisconnectNotification
        assertEquals(deserialized.variable.reasonCode, reason)
        assertEquals(disconnect, deserialized)
    }

    @Test
    fun serializeDeserializeAdministrativeAction() {
        val reason = ADMINISTRATIVE_ACTION
        val disconnect = DisconnectNotification(VariableHeader(reason))
        val buffer = BufferFactory.Default.allocate(disconnect.packetSize())
        disconnect.serialize(buffer)

        buffer.resetForRead()
        val deserialized = ControlPacketV5.from(buffer) as DisconnectNotification
        assertEquals(deserialized.variable.reasonCode, reason)
        assertEquals(disconnect, deserialized)
    }

    @Test
    fun serializeDeserializePayloadFormatInvalid() {
        val reason = PAYLOAD_FORMAT_INVALID
        val disconnect = DisconnectNotification(VariableHeader(reason))
        val buffer = BufferFactory.Default.allocate(4)
        disconnect.serialize(buffer)

        buffer.resetForRead()
        val deserialized = ControlPacketV5.from(buffer) as DisconnectNotification
        assertEquals(deserialized.variable.reasonCode, reason)
        assertEquals(disconnect, deserialized)
    }

    @Test
    fun serializeDeserializeRetainNotSupported() {
        val reason = RETAIN_NOT_SUPPORTED
        val disconnect = DisconnectNotification(VariableHeader(reason))
        val buffer = BufferFactory.Default.allocate(4)
        disconnect.serialize(buffer)

        buffer.resetForRead()
        val deserialized = ControlPacketV5.from(buffer) as DisconnectNotification
        assertEquals(deserialized.variable.reasonCode, reason)
        assertEquals(disconnect, deserialized)
    }

    @Test
    fun serializeDeserializeQosNotSupported() {
        val reason = QOS_NOT_SUPPORTED
        val disconnect = DisconnectNotification(VariableHeader(reason))
        val buffer = BufferFactory.Default.allocate(4)
        disconnect.serialize(buffer)

        buffer.resetForRead()
        val deserialized = ControlPacketV5.from(buffer) as DisconnectNotification
        assertEquals(deserialized.variable.reasonCode, reason)
        assertEquals(disconnect, deserialized)
    }

    @Test
    fun serializeDeserializeUseAnotherServer() {
        val reason = USE_ANOTHER_SERVER
        val disconnect = DisconnectNotification(VariableHeader(reason))
        val buffer = BufferFactory.Default.allocate(4)
        disconnect.serialize(buffer)

        buffer.resetForRead()
        val deserialized = ControlPacketV5.from(buffer) as DisconnectNotification
        assertEquals(deserialized.variable.reasonCode, reason)
        assertEquals(disconnect, deserialized)
    }

    @Test
    fun serializeDeserializeServerMoved() {
        val reason = SERVER_MOVED
        val disconnect = DisconnectNotification(VariableHeader(reason))
        val buffer = BufferFactory.Default.allocate(4)
        disconnect.serialize(buffer)

        buffer.resetForRead()
        val deserialized = ControlPacketV5.from(buffer) as DisconnectNotification
        assertEquals(deserialized.variable.reasonCode, reason)
        assertEquals(disconnect, deserialized)
    }

    @Test
    fun serializeDeserializeSharedSubscriptionNotSupported() {
        val reason = SHARED_SUBSCRIPTIONS_NOT_SUPPORTED
        val disconnect = DisconnectNotification(VariableHeader(reason))
        val buffer = BufferFactory.Default.allocate(4)
        disconnect.serialize(buffer)

        buffer.resetForRead()
        val deserialized = ControlPacketV5.from(buffer) as DisconnectNotification
        assertEquals(deserialized.variable.reasonCode, reason)
        assertEquals(disconnect, deserialized)
    }

    @Test
    fun serializeDeserializeConnectionRateExceeded() {
        val reason = CONNECTION_RATE_EXCEEDED
        val disconnect = DisconnectNotification(VariableHeader(reason))
        val buffer = BufferFactory.Default.allocate(4)
        disconnect.serialize(buffer)

        buffer.resetForRead()
        val deserialized = ControlPacketV5.from(buffer) as DisconnectNotification
        assertEquals(deserialized.variable.reasonCode, reason)
        assertEquals(disconnect, deserialized)
    }

    @Test
    fun serializeDeserializeMaximumConnectionTime() {
        val reason = MAXIMUM_CONNECTION_TIME
        val disconnect = DisconnectNotification(VariableHeader(reason))
        val buffer = BufferFactory.Default.allocate(4)
        disconnect.serialize(buffer)

        buffer.resetForRead()
        val deserialized = ControlPacketV5.from(buffer) as DisconnectNotification
        assertEquals(deserialized.variable.reasonCode, reason)
        assertEquals(disconnect, deserialized)
    }

    @Test
    fun serializeDeserializeSubscriptionIdentifiersNotSupported() {
        val reason = SUBSCRIPTION_IDENTIFIERS_NOT_SUPPORTED
        val disconnect = DisconnectNotification(VariableHeader(reason))
        val buffer = BufferFactory.Default.allocate(4)
        disconnect.serialize(buffer)

        buffer.resetForRead()
        val deserialized = ControlPacketV5.from(buffer) as DisconnectNotification
        assertEquals(deserialized.variable.reasonCode, reason)
        assertEquals(disconnect, deserialized)
    }

    @Test
    fun serializeDeserializeWildcardSubscriptionsNotSupported() {
        val reason = WILDCARD_SUBSCRIPTIONS_NOT_SUPPORTED
        val disconnect = DisconnectNotification(VariableHeader(reason))
        val buffer = BufferFactory.Default.allocate(4)
        disconnect.serialize(buffer)

        buffer.resetForRead()
        val deserialized = ControlPacketV5.from(buffer) as DisconnectNotification
        assertEquals(deserialized.variable.reasonCode, reason)
        assertEquals(disconnect, deserialized)
    }

    /**
     * Regression: A broker may send DISCONNECT with remainingLength=0 (clean disconnect,
     * no reason code, no properties). Before the fix, the parser tried to read bytes
     * from an empty buffer.
     */
    @Test
    fun remainingLength0CleanDisconnect() {
        val buffer = BufferFactory.Default.allocate(2)
        buffer.writeByte(0b11100000.toByte()) // DISCONNECT fixed header
        buffer.writeByte(0) // remaining length = 0
        buffer.resetForRead()
        val disconnect = ControlPacketV5.from(buffer) as DisconnectNotification
        assertEquals(NORMAL_DISCONNECTION, disconnect.variable.reasonCode)
    }

    /**
     * Regression: A broker may send DISCONNECT with remainingLength=1 (reason code only,
     * no properties). Before the fix, the parser tried to read property length after
     * the reason code, reading past the buffer.
     */
    @Test
    fun remainingLength1ReasonCodeOnly() {
        val buffer = BufferFactory.Default.allocate(3)
        buffer.writeByte(0b11100000.toByte()) // DISCONNECT fixed header
        buffer.writeByte(1) // remaining length = 1
        buffer.writeUByte(MALFORMED_PACKET.byte.toUByte()) // reason code only
        buffer.resetForRead()
        val disconnect = ControlPacketV5.from(buffer) as DisconnectNotification
        assertEquals(MALFORMED_PACKET, disconnect.variable.reasonCode)
    }

    @Test
    fun serializeDeserializeInvalid() {
        try {
            VariableHeader(BANNED)
            fail()
        } catch (e: MalformedPacketException) {
        }
    }
}
