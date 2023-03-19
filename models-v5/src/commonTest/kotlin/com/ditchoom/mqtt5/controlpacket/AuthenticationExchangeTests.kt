package com.ditchoom.mqtt5.controlpacket

import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.allocate
import com.ditchoom.buffer.toReadBuffer
import com.ditchoom.mqtt.MalformedPacketException
import com.ditchoom.mqtt.ProtocolError
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.readMqttUtf8StringNotValidatedSized
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.readVariableByteInteger
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.variableByteSize
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.writeVariableByteInteger
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.BANNED
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.SUCCESS
import com.ditchoom.mqtt5.controlpacket.AuthenticationExchange.VariableHeader
import com.ditchoom.mqtt5.controlpacket.AuthenticationExchange.VariableHeader.Properties
import com.ditchoom.mqtt5.controlpacket.properties.Authentication
import com.ditchoom.mqtt5.controlpacket.properties.AuthenticationData
import com.ditchoom.mqtt5.controlpacket.properties.AuthenticationMethod
import com.ditchoom.mqtt5.controlpacket.properties.ReasonString
import com.ditchoom.mqtt5.controlpacket.properties.UserProperty
import com.ditchoom.mqtt5.controlpacket.properties.WillDelayInterval
import com.ditchoom.mqtt5.controlpacket.properties.readProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.fail

class AuthenticationExchangeTests {

    private val emptyStringBuffer = PlatformBuffer.allocate(0)
    private val buffer123 = PlatformBuffer.allocate(3).also {
        it.write("123".toReadBuffer(Charset.UTF8))
        it.resetForRead()
    }

    @Test
    fun serializationByteVerification() {
        val buffer = PlatformBuffer.allocate(14)
        val props = Properties(Authentication("test", emptyStringBuffer))
        val disconnect = AuthenticationExchange(VariableHeader(SUCCESS, props))
        disconnect.serialize(buffer)
        buffer.resetForRead()
        // fixed header
        assertEquals(0b11110000.toUByte(), buffer.readUnsignedByte(), "byte1 fixed header")
        assertEquals(12, buffer.readVariableByteInteger(), "byte2 fixed header remaining length")
        // variable header
        assertEquals(SUCCESS.byte, buffer.readUnsignedByte(), "byte0 variable header reason code")
        assertEquals(10, buffer.readVariableByteInteger(), "property length")
        assertEquals(
            0x15.toUByte(),
            buffer.readUnsignedByte(),
            "identifier of the authentication method"
        )
        assertEquals(4u, buffer.readUnsignedShort())
        assertEquals("test", buffer.readString(4, Charset.UTF8), "authentication method value")
    }

    @Test
    fun serializeDeserializeVariableHeader() {
        val buffer = PlatformBuffer.allocate(16)
        val variableHeader =
            VariableHeader(SUCCESS, Properties(Authentication("hello", buffer123)))
        variableHeader.serialize(buffer)
        buffer.resetForRead()
        assertEquals(SUCCESS.byte, buffer.readUnsignedByte(), "reason code")
        assertEquals(14, buffer.readVariableByteInteger(), "property length")
        assertEquals(0x15, buffer.readVariableByteInteger(), "property identifier auth method")
        assertEquals(
            "hello",
            buffer.readMqttUtf8StringNotValidatedSized().second.toString(),
            "auth method string"
        )
        assertEquals(0x16, buffer.readVariableByteInteger(), "property identifier auth data")
        assertEquals(3u, buffer.readUnsignedShort(), "auth data size")
        assertEquals("123", buffer.readString(3, Charset.UTF8).toString(), "auth data payload")
    }

    @Test
    fun serializeDeserialize() {
        val buffer = PlatformBuffer.allocate(14)
        val props = Properties(Authentication("test", emptyStringBuffer))
        val disconnect = AuthenticationExchange(VariableHeader(SUCCESS, props))
        disconnect.serialize(buffer)
        buffer.resetForRead()
        val deserialized = ControlPacketV5.from(buffer) as AuthenticationExchange
        assertEquals(deserialized.variable.reasonCode, SUCCESS)
        buffer.resetForRead()
        assertEquals(0b11110000.toUByte(), buffer.readUnsignedByte(), "control packet code")
        assertEquals(12, buffer.readVariableByteInteger(), "remaining length")
        assertEquals(SUCCESS.byte, buffer.readUnsignedByte(), "reason code")
        assertEquals(10, buffer.readVariableByteInteger(), "property length")
        assertEquals(0x15, buffer.readVariableByteInteger(), "property identifier auth method")
        assertEquals(
            "test",
            buffer.readMqttUtf8StringNotValidatedSized().second.toString(),
            "auth method string"
        )
        assertEquals(0x16, buffer.readVariableByteInteger(), "property identifier auth data")
        assertEquals(0u, buffer.readUnsignedShort(), "auth data size")
        assertEquals("", buffer.readString(0, Charset.UTF8), "auth data payload")
    }

    @Test
    fun serializeDeserializeInvalid() {
        try {
            VariableHeader(BANNED, Properties(Authentication("test", emptyStringBuffer)))
            fail()
        } catch (e: MalformedPacketException) {
        }
    }

    @Test
    fun reasonString() {
        val buffer = PlatformBuffer.allocate(18)
        val props = Properties(
            Authentication("2", emptyStringBuffer),
            reasonString = "yolo"
        )
        val header = VariableHeader(SUCCESS, properties = props)
        val expected = AuthenticationExchange(header)
        expected.serialize(buffer)
        buffer.resetForRead()
        val actual = ControlPacketV5.from(buffer) as AuthenticationExchange
        assertEquals("yolo", actual.variable.properties.reasonString.toString())
    }

    @Test
    fun reasonStringMultipleTimesThrowsProtocolError() {
        val obj1 = ReasonString("yolo")
        val obj2 = obj1.copy()
        val buffer = PlatformBuffer.allocate(20)
        val size = obj1.size() + obj2.size()
        buffer.writeVariableByteInteger(size)
        obj1.write(buffer)
        obj2.write(buffer)
        buffer.resetForRead()
        try {
            Properties.from(buffer.readProperties())
            fail()
        } catch (e: ProtocolError) {
        }
    }

    @Test
    fun variableHeaderPropertyByteValidation() {
        val props = Properties.from(
            setOf(
                AuthenticationMethod("2"),
                UserProperty("key", "value")
            )
        )
        val userPropertyResult = props.userProperty
        for ((key, value) in userPropertyResult) {
            assertEquals(key, "key")
            assertEquals(value, "value")
        }
        assertEquals(userPropertyResult.size, 1)

        val buffer = PlatformBuffer.allocate(17)
        AuthenticationExchange(VariableHeader(SUCCESS, properties = props)).serialize(buffer)
        buffer.resetForRead()
        // fixed header
        assertEquals(0b11110000.toUByte(), buffer.readUnsignedByte(), "byte1 fixed header")
        assertEquals(15, buffer.readVariableByteInteger(), "byte2 fixed header remaining length")
        // variable header
        assertEquals(SUCCESS.byte, buffer.readUnsignedByte(), "byte0 variable header reason code")
        assertEquals(13, buffer.readVariableByteInteger(), "property length")
        assertEquals(0x26.toUByte(), buffer.readUnsignedByte(), "user property flag")
        assertEquals(
            "key",
            buffer.readMqttUtf8StringNotValidatedSized().second.toString(),
            "user property key"
        )
        assertEquals(
            "value",
            buffer.readMqttUtf8StringNotValidatedSized().second.toString(),
            "user property value"
        )
    }

    @Test
    fun variableHeaderPropertyUserProperty() {
        val props = Properties.from(
            setOf(
                AuthenticationMethod("2"),
                UserProperty("key", "value")
            )
        )
        val userPropertyResult = props.userProperty
        for ((key, value) in userPropertyResult) {
            assertEquals(key, "key")
            assertEquals(value, "value")
        }
        assertEquals(userPropertyResult.size, 1)

        val buffer = PlatformBuffer.allocate(21)
        AuthenticationExchange(VariableHeader(SUCCESS, properties = props)).serialize(buffer)
        buffer.resetForRead()
        val requestRead = ControlPacketV5.from(buffer) as AuthenticationExchange
        val (key, value) = requestRead.variable.properties.userProperty.first()
        assertEquals(key.toString(), "key")
        assertEquals(value.toString(), "value")
    }

    @Test
    fun authMethodMultipleTimesThrowsProtocolError() {
        val obj1 = AuthenticationMethod("yolo")
        val obj2 = obj1.copy()
        val size = obj1.size() + obj2.size()
        val buffer1 = PlatformBuffer.allocate(size + variableByteSize(size))
        buffer1.writeVariableByteInteger(size)
        obj1.write(buffer1)
        obj2.write(buffer1)
        buffer1.resetForRead()
        assertFailsWith<ProtocolError>("should throw error because auth method is added twice") {
            Properties.from(buffer1.readProperties())
        }
    }

    @Test
    fun authDataMultipleTimesThrowsProtocolError() {
        val method = AuthenticationMethod("yolo")
        val authData = AuthenticationData(buffer123)
        val methodSize = method.size()
        val authDataSize = authData.size()
        val size = methodSize + authDataSize + authDataSize + 1
        val buffer = PlatformBuffer.allocate(size)
        buffer.writeVariableByteInteger(size - 1)
        method.write(buffer)
        authData.write(buffer)

        val obj2 = authData.copy()
        obj2.write(buffer)
        buffer.resetForRead()
        assertFailsWith<ProtocolError>("should throw error because auth data is added twice") {
            Properties.from(buffer.readProperties())
        }
    }

    @Test
    fun invalidReasonCode() {
        try {
            VariableHeader(BANNED, Properties(Authentication("test", emptyStringBuffer)))
            fail()
        } catch (e: MalformedPacketException) {
        }
    }

    @Test
    fun invalidPropertyThrowsMalformedException() {
        try {
            Properties.from(setOf(WillDelayInterval(2)))
            fail()
        } catch (e: MalformedPacketException) {
        }
    }
}
