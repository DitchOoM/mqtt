package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.mqtt.MqttWarning
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.readVariableByteInteger
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.QualityOfService.AT_MOST_ONCE
import com.ditchoom.mqtt.controlpacket.QualityOfService.EXACTLY_ONCE
import com.ditchoom.mqtt.controlpacket.TopicName
import com.ditchoom.mqtt.controlpacket.WillConfig
import com.ditchoom.mqtt3.controlpacket.ConnectionRequest.VariableHeader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class ConnectionRequestTests {
    @Test
    fun fixedHeaderByte1() {
        val connectionRequest = ConnectionRequest()
        val buffer = BufferFactory.Default.allocate(14)
        serializeV4(connectionRequest, buffer)
        buffer.resetForRead()
        assertEquals(buffer.readByte(), 0b00010000, "invalid byte 1 on the CONNECT fixed header")
    }

    @Test
    fun fixedHeaderRemainingLength() {
        val connectionRequest = ConnectionRequest()
        val buffer = BufferFactory.Default.allocate(14)
        serializeV4(connectionRequest, buffer)
        buffer.resetForRead()
        buffer.readByte() // skip first byte
        val remainingLength = buffer.readVariableByteInteger()
        assertEquals(
            12,
            remainingLength,
            "invalid remaining length on the CONNECT fixed header",
        )
    }

    @Test
    fun variableHeaderProtocolNameByte1() {
        val connectionRequest = ConnectionRequest()
        val buffer = BufferFactory.Default.allocate(14)
        serializeV4(connectionRequest, buffer)
        buffer.resetForRead()
        buffer.readByte() // skip first byte
        buffer.readVariableByteInteger() // skip the remaining length
        val byte1ProtocolName = buffer.readByte()
        assertEquals(byte1ProtocolName, 0, "invalid byte 1 on the CONNECT variable header")
    }

    @Test
    fun variableHeaderProtocolNameByte2() {
        val connectionRequest = ConnectionRequest()
        val buffer = BufferFactory.Default.allocate(14)
        serializeV4(connectionRequest, buffer)
        buffer.resetForRead()
        buffer.readByte() // skip first byte
        buffer.readVariableByteInteger() // skip the remaining length
        buffer.readByte() // Length MSB (0)
        val byte = buffer.readByte()
        assertEquals(byte, 0b100, "invalid byte 2 on the CONNECT variable header")
        assertEquals(byte, 4, "invalid byte 2 on the CONNECT variable header")
    }

    @Test
    fun variableHeaderProtocolNameByte3() {
        val connectionRequest = ConnectionRequest()
        val buffer = BufferFactory.Default.allocate(14)
        serializeV4(connectionRequest, buffer)
        buffer.resetForRead()
        buffer.readByte() // skip first byte
        buffer.readVariableByteInteger() // skip the remaining length
        buffer.readByte() // Length MSB (0)
        buffer.readByte() // Length LSB (4)
        val byte = buffer.readByte()
        assertEquals(byte, 0b01001101, "invalid byte 3 on the CONNECT variable header")
        assertEquals(byte.toInt().toChar(), 'M', "invalid byte 3 on the CONNECT variable header")
    }

    @Test
    fun variableHeaderProtocolNameByte4() {
        val connectionRequest = ConnectionRequest()
        val buffer = BufferFactory.Default.allocate(14)
        serializeV4(connectionRequest, buffer)
        buffer.resetForRead()
        buffer.readByte() // skip first byte
        buffer.readVariableByteInteger() // skip the remaining length
        buffer.readByte() // Length MSB (0)
        buffer.readByte() // Length LSB (4)
        buffer.readByte() // 'M' or 0b01001101
        val byte = buffer.readByte()
        assertEquals(byte, 0b01010001, "invalid byte 4 on the CONNECT variable header")
        assertEquals(byte.toInt().toChar(), 'Q', "invalid byte 4 on the CONNECT variable header")
    }

    @Test
    fun variableHeaderProtocolNameByte5() {
        val connectionRequest = ConnectionRequest()
        val buffer = BufferFactory.Default.allocate(14)
        serializeV4(connectionRequest, buffer)
        buffer.resetForRead()
        buffer.readByte() // skip first byte
        buffer.readVariableByteInteger() // skip the remaining length
        buffer.readByte() // Length MSB (0)
        buffer.readByte() // Length LSB (4)
        buffer.readByte() // 'M' or 0b01001101
        buffer.readByte() // 'Q' or 0b01010001
        val byte = buffer.readByte()
        assertEquals(byte, 0b01010100, "invalid byte 5 on the CONNECT variable header")
        assertEquals(byte.toInt().toChar(), 'T', "invalid byte 5 on the CONNECT variable header")
    }

    @Test
    fun variableHeaderProtocolNameByte6() {
        val connectionRequest = ConnectionRequest()
        val buffer = BufferFactory.Default.allocate(14)
        serializeV4(connectionRequest, buffer)
        buffer.resetForRead()
        buffer.readByte() // skip the first byte
        buffer.readVariableByteInteger() // skip the remaining length
        buffer.readByte() // Length MSB (0)
        buffer.readByte() // Length LSB (4)
        buffer.readByte() // 'M' or 0b01001101
        buffer.readByte() // 'Q' or 0b01010001
        buffer.readByte() // 'T' or 0b01010100
        val byte = buffer.readByte()
        assertEquals(byte, 0b01010100, "invalid byte 6 on the CONNECT variable header")
        assertEquals(byte.toInt().toChar(), 'T', "invalid byte 6 on the CONNECT variable header")
    }

    @Test
    fun variableHeaderProtocolVersionByte7() {
        val connectionRequest = ConnectionRequest()
        val buffer = BufferFactory.Default.allocate(14)
        serializeV4(connectionRequest, buffer)
        buffer.resetForRead()
        buffer.readByte() // skip the first byte
        buffer.readVariableByteInteger() // skip the remaining length
        buffer.readByte() // Length MSB (0)
        buffer.readByte() // Length LSB (4)
        buffer.readByte() // 'M' or 0b01001101
        buffer.readByte() // 'Q' or 0b01010001
        buffer.readByte() // 'T' or 0b01010100
        buffer.readByte() // 'T' or 0b01010100
        val byte = buffer.readByte()
        assertEquals(byte, 0b00000100, "invalid byte 7 on the CONNECT variable header")
        assertEquals(byte, 4, "invalid byte 7 on the CONNECT variable header")
    }

    @Test
    fun variableHeaderConnectFlagsByte8AllFalse() {
        val connectionRequest = ConnectionRequest(VariableHeader(willQos = AT_MOST_ONCE))
        val buffer = BufferFactory.Default.allocate(14)
        serializeV4(connectionRequest, buffer)
        buffer.resetForRead()
        buffer.readByte() // skip the first byte
        buffer.readVariableByteInteger() // skip the remaining length
        buffer.readByte() // Length MSB (0)
        buffer.readByte() // Length LSB (4)
        buffer.readByte() // 'M' or 0b01001101
        buffer.readByte() // 'Q' or 0b01010001
        buffer.readByte() // 'T' or 0b01010100
        buffer.readByte() // 'T' or 0b01010100
        buffer.readByte() // 5 or 0b00000101
        val byte = buffer.readUnsignedByte()
        val connectFlagsPackedInByte = byte.toInt()
        val usernameFlag = connectFlagsPackedInByte.shr(7) == 1
        assertFalse(
            usernameFlag,
            "invalid byte 8 bit 7 on the CONNECT variable header for username flag",
        )
        val passwordFlag = connectFlagsPackedInByte.shl(1).shr(7) == 1
        assertFalse(
            passwordFlag,
            "invalid byte 8 bit 6 on the CONNECT variable header for password flag",
        )
        val willRetain = connectFlagsPackedInByte.shl(2).shr(7) == 1
        assertFalse(
            willRetain,
            "invalid byte 8 bit 5 on the CONNECT variable header for willRetain flag",
        )
        val willQosBit4 = connectFlagsPackedInByte.shl(3).shr(7) == 1
        assertFalse(
            willQosBit4,
            "invalid byte 8 bit 4 on the CONNECT variable header for willQosBit4 flag",
        )
        val willQosBit3 = connectFlagsPackedInByte.shl(4).shr(7) == 1
        assertFalse(
            willQosBit3,
            "invalid byte 8 bit 3 on the CONNECT variable header for willQosBit3 flag",
        )
        val willQos = QualityOfService.fromBooleans(willQosBit4, willQosBit3)
        assertEquals(
            willQos,
            AT_MOST_ONCE,
            "invalid byte 8 qos on the CONNECT variable header for willQos flag",
        )
        val willFlag = connectFlagsPackedInByte.shl(5).shr(7) == 1
        assertFalse(
            willFlag,
            "invalid byte 8 bit 2 on the CONNECT variable header for willFlag flag",
        )
        val cleanStart = connectFlagsPackedInByte.shl(6).shr(7) == 1
        assertFalse(
            cleanStart,
            "invalid byte 8 bit 1 on the CONNECT variable header for cleanSession flag",
        )
        val reserved = connectFlagsPackedInByte.shl(7).shr(7) == 1
        assertFalse(
            reserved,
            "invalid byte 8 bit 0 on the CONNECT variable header for reserved flag",
        )
    }

    @Test
    fun variableHeaderConnectFlagsByte8HasUsername() {
        val connectionRequest =
            ConnectionRequest(
                VariableHeader(willQos = AT_MOST_ONCE, hasUserName = true),
                ConnectionRequest.Payload(userName = "yolo"),
            )
        val buffer = BufferFactory.Default.allocate(20)
        serializeV4(connectionRequest, buffer)
        buffer.resetForRead()
        buffer.readByte() // skip the first byte
        buffer.readVariableByteInteger() // skip the remaining length
        buffer.readByte() // Length MSB (0)
        buffer.readByte() // Length LSB (4)
        buffer.readByte() // 'M' or 0b01001101
        buffer.readByte() // 'Q' or 0b01010001
        buffer.readByte() // 'T' or 0b01010100
        buffer.readByte() // 'T' or 0b01010100
        buffer.readByte() // 5 or 0b00000101
        val byte = buffer.readUnsignedByte()
        val connectFlagsPackedInByte = byte.toInt()
        val usernameFlag = connectFlagsPackedInByte.shr(7) == 1
        assertTrue(
            usernameFlag,
            "invalid byte 8 bit 7 on the CONNECT variable header for username flag",
        )
        val passwordFlag = connectFlagsPackedInByte.shl(1).shr(7) == 1
        assertFalse(
            passwordFlag,
            "invalid byte 8 bit 6 on the CONNECT variable header for password flag",
        )
        val willRetain = connectFlagsPackedInByte.shl(2).shr(7) == 1
        assertFalse(
            willRetain,
            "invalid byte 8 bit 5 on the CONNECT variable header for willRetain flag",
        )
        val willQosBit4 = connectFlagsPackedInByte.shl(3).shr(7) == 1
        assertFalse(
            willQosBit4,
            "invalid byte 8 bit 4 on the CONNECT variable header for willQosBit4 flag",
        )
        val willQosBit3 = connectFlagsPackedInByte.shl(4).shr(7) == 1
        assertFalse(
            willQosBit3,
            "invalid byte 8 bit 3 on the CONNECT variable header for willQosBit3 flag",
        )
        val willQos = QualityOfService.fromBooleans(willQosBit4, willQosBit3)
        assertEquals(
            willQos,
            AT_MOST_ONCE,
            "invalid byte 8 qos on the CONNECT variable header for willQos flag",
        )
        val willFlag = connectFlagsPackedInByte.shl(5).shr(7) == 1
        assertFalse(
            willFlag,
            "invalid byte 8 bit 2 on the CONNECT variable header for willFlag flag",
        )
        val cleanStart = connectFlagsPackedInByte.shl(6).shr(7) == 1
        assertFalse(
            cleanStart,
            "invalid byte 8 bit 1 on the CONNECT variable header for cleanSession flag",
        )
        val reserved = connectFlagsPackedInByte.shl(7).shr(7) == 1
        assertFalse(
            reserved,
            "invalid byte 8 bit 0 on the CONNECT variable header for reserved flag",
        )
    }

    @Test
    fun variableHeaderConnectFlagsByte8HasPassword() {
        val connectionRequest =
            ConnectionRequest(
                VariableHeader(willQos = AT_MOST_ONCE, hasPassword = true),
                ConnectionRequest.Payload(password = "yolo"),
            )
        val buffer = BufferFactory.Default.allocate(20)
        serializeV4(connectionRequest, buffer)
        buffer.resetForRead()
        val actual = decodeV4(buffer)
        assertEquals(actual, connectionRequest)
        buffer.resetForRead()
        buffer.readByte() // skip the first byte
        buffer.readVariableByteInteger() // skip the remaining length
        buffer.readByte() // Length MSB (0)
        buffer.readByte() // Length LSB (4)
        buffer.readByte() // 'M' or 0b01001101
        buffer.readByte() // 'Q' or 0b01010001
        buffer.readByte() // 'T' or 0b01010100
        buffer.readByte() // 'T' or 0b01010100
        buffer.readByte() // 5 or 0b00000101
        val byte = buffer.readUnsignedByte()
        val connectFlagsPackedInByte = byte.toInt()
        val usernameFlag = connectFlagsPackedInByte.shr(7) == 1
        assertFalse(
            usernameFlag,
            "invalid byte 8 bit 7 on the CONNECT variable header for username flag",
        )
        val passwordFlag = connectFlagsPackedInByte.shl(1).shr(7) == 1
        assertTrue(
            passwordFlag,
            "invalid byte 8 bit 6 on the CONNECT variable header for password flag",
        )
        val willRetain = connectFlagsPackedInByte.shl(2).shr(7) == 1
        assertFalse(
            willRetain,
            "invalid byte 8 bit 5 on the CONNECT variable header for willRetain flag",
        )
        val willQosBit4 = connectFlagsPackedInByte.shl(3).shr(7) == 1
        assertFalse(
            willQosBit4,
            "invalid byte 8 bit 4 on the CONNECT variable header for willQosBit4 flag",
        )
        val willQosBit3 = connectFlagsPackedInByte.shl(4).shr(7) == 1
        assertFalse(
            willQosBit3,
            "invalid byte 8 bit 3 on the CONNECT variable header for willQosBit3 flag",
        )
        val willQos = QualityOfService.fromBooleans(willQosBit4, willQosBit3)
        assertEquals(
            willQos,
            AT_MOST_ONCE,
            "invalid byte 8 qos on the CONNECT variable header for willQos flag",
        )
        val willFlag = connectFlagsPackedInByte.shl(5).shr(7) == 1
        assertFalse(
            willFlag,
            "invalid byte 8 bit 2 on the CONNECT variable header for willFlag flag",
        )
        val cleanStart = connectFlagsPackedInByte.shl(6).shr(7) == 1
        assertFalse(
            cleanStart,
            "invalid byte 8 bit 1 on the CONNECT variable header for cleanSession flag",
        )
        val reserved = connectFlagsPackedInByte.shl(7).shr(7) == 1
        assertFalse(
            reserved,
            "invalid byte 8 bit 0 on the CONNECT variable header for reserved flag",
        )
    }

    @Test
    fun variableHeaderConnectFlagsByte8HasWillRetainCheckWarning() {
        assertNotNull(
            VariableHeader(willQos = AT_MOST_ONCE, willRetain = true).validateOrGetWarning(),
            "should of provided an warning",
        )
    }

    @Test
    fun variableHeaderConnectFlagsByte8HasWillRetain() {
        val vh = VariableHeader(willQos = AT_MOST_ONCE, willRetain = true)
        val connectionRequest = ConnectionRequest(vh)
        val buffer = BufferFactory.Default.allocate(14)
        serializeV4(connectionRequest, buffer)
        buffer.resetForRead()
        buffer.readByte() // skip the first byte
        buffer.readVariableByteInteger() // skip the remaining length
        buffer.readByte() // Length MSB (0)
        buffer.readByte() // Length LSB (4)
        buffer.readByte() // 'M' or 0b01001101
        buffer.readByte() // 'Q' or 0b01010001
        buffer.readByte() // 'T' or 0b01010100
        buffer.readByte() // 'T' or 0b01010100
        buffer.readByte() // 5 or 0b00000101
        val byte = buffer.readUnsignedByte()
        val connectFlagsPackedInByte = byte.toInt()
        val usernameFlag = connectFlagsPackedInByte.shr(7) == 1
        assertFalse(
            usernameFlag,
            "invalid byte 8 bit 7 on the CONNECT variable header for username flag",
        )
        val passwordFlag = connectFlagsPackedInByte.shl(1).shr(7) == 1
        assertFalse(
            passwordFlag,
            "invalid byte 8 bit 6 on the CONNECT variable header for password flag",
        )
        val willRetain = connectFlagsPackedInByte.shl(2).shr(7) == 1
        assertTrue(
            willRetain,
            "invalid byte 8 bit 5 on the CONNECT variable header for willRetain flag",
        )
        val willQosBit4 = connectFlagsPackedInByte.shl(3).shr(7) == 1
        assertFalse(
            willQosBit4,
            "invalid byte 8 bit 4 on the CONNECT variable header for willQosBit4 flag",
        )
        val willQosBit3 = connectFlagsPackedInByte.shl(4).shr(7) == 1
        assertFalse(
            willQosBit3,
            "invalid byte 8 bit 3 on the CONNECT variable header for willQosBit3 flag",
        )
        val willQos = QualityOfService.fromBooleans(willQosBit4, willQosBit3)
        assertEquals(
            willQos,
            AT_MOST_ONCE,
            "invalid byte 8 qos on the CONNECT variable header for willQos flag",
        )
        val willFlag = connectFlagsPackedInByte.shl(5).shr(7) == 1
        assertFalse(
            willFlag,
            "invalid byte 8 bit 2 on the CONNECT variable header for willFlag flag",
        )
        val cleanStart = connectFlagsPackedInByte.shl(6).shr(7) == 1
        assertFalse(
            cleanStart,
            "invalid byte 8 bit 1 on the CONNECT variable header for cleanSession flag",
        )
        val reserved = connectFlagsPackedInByte.shl(7).shr(7) == 1
        assertFalse(
            reserved,
            "invalid byte 8 bit 0 on the CONNECT variable header for reserved flag",
        )
    }

    @Test
    fun variableHeaderConnectFlagsByte8HasQos1() {
        val connectionRequest = ConnectionRequest()
        val buffer = BufferFactory.Default.allocate(14)
        serializeV4(connectionRequest, buffer)
        buffer.resetForRead()
        buffer.readByte() // skip the first byte
        buffer.readVariableByteInteger() // skip the remaining length
        buffer.readByte() // Length MSB (0)
        buffer.readByte() // Length LSB (4)
        buffer.readByte() // 'M' or 0b01001101
        buffer.readByte() // 'Q' or 0b01010001
        buffer.readByte() // 'T' or 0b01010100
        buffer.readByte() // 'T' or 0b01010100
        buffer.readByte() // 5 or 0b00000101
        val byte = buffer.readUnsignedByte()
        val connectFlagsPackedInByte = byte.toInt()
        val usernameFlag = connectFlagsPackedInByte.shr(7) == 1
        assertFalse(
            usernameFlag,
            "invalid byte 8 bit 7 on the CONNECT variable header for username flag",
        )
        val passwordFlag = connectFlagsPackedInByte.shl(1).shr(7) == 1
        assertFalse(
            passwordFlag,
            "invalid byte 8 bit 6 on the CONNECT variable header for password flag",
        )
        val willRetain = connectFlagsPackedInByte.shl(2).shr(7) == 1
        assertFalse(
            willRetain,
            "invalid byte 8 bit 5 on the CONNECT variable header for willRetain flag",
        )
        val willQosBit4 = connectFlagsPackedInByte.shl(3).shr(7) == 1
        assertFalse(
            willQosBit4,
            "invalid byte 8 bit 4 on the CONNECT variable header for willQosBit4 flag",
        )
        val willQosBit3 = connectFlagsPackedInByte.shl(4).shr(7) == 1
        assertFalse(
            willQosBit3,
            "invalid byte 8 bit 3 on the CONNECT variable header for willQosBit3 flag",
        )
        val willQos = QualityOfService.fromBooleans(willQosBit4, willQosBit3)
        assertEquals(
            willQos,
            AT_MOST_ONCE,
            "invalid byte 8 qos on the CONNECT variable header for willQos flag",
        )
        val willFlag = connectFlagsPackedInByte.shl(5).shr(7) == 1
        assertFalse(
            willFlag,
            "invalid byte 8 bit 2 on the CONNECT variable header for willFlag flag",
        )
        val cleanStart = connectFlagsPackedInByte.shl(6).shr(7) == 1
        assertFalse(
            cleanStart,
            "invalid byte 8 bit 1 on the CONNECT variable header for cleanSession flag",
        )
        val reserved = connectFlagsPackedInByte.shl(7).shr(7) == 1
        assertFalse(
            reserved,
            "invalid byte 8 bit 0 on the CONNECT variable header for reserved flag",
        )
    }

    @Test
    fun variableHeaderConnectFlagsByte8HasQos2() {
        val connectionRequest = ConnectionRequest(VariableHeader(willQos = EXACTLY_ONCE))
        val buffer = BufferFactory.Default.allocate(14)
        serializeV4(connectionRequest, buffer)
        buffer.resetForRead()
        buffer.readByte() // skip the first byte
        buffer.readVariableByteInteger() // skip the remaining length
        buffer.readByte() // Length MSB (0)
        buffer.readByte() // Length LSB (4)
        buffer.readByte() // 'M' or 0b01001101
        buffer.readByte() // 'Q' or 0b01010001
        buffer.readByte() // 'T' or 0b01010100
        buffer.readByte() // 'T' or 0b01010100
        buffer.readByte() // 5 or 0b00000101
        val byte = buffer.readUnsignedByte()
        val connectFlagsPackedInByte = byte.toInt()
        val usernameFlag = connectFlagsPackedInByte.shr(7) == 1
        assertFalse(
            usernameFlag,
            "invalid byte 8 bit 7 on the CONNECT variable header for username flag",
        )
        val passwordFlag = connectFlagsPackedInByte.shl(1).shr(7) == 1
        assertFalse(
            passwordFlag,
            "invalid byte 8 bit 6 on the CONNECT variable header for password flag",
        )
        val willRetain = connectFlagsPackedInByte.shl(2).shr(7) == 1
        assertFalse(
            willRetain,
            "invalid byte 8 bit 5 on the CONNECT variable header for willRetain flag",
        )
        val willQosBit4 = connectFlagsPackedInByte.shl(3).shr(7) == 1
        assertTrue(
            willQosBit4,
            "invalid byte 8 bit 4 on the CONNECT variable header for willQosBit4 flag",
        )
        val willQosBit3 = connectFlagsPackedInByte.shl(4).shr(7) == 1
        assertFalse(
            willQosBit3,
            "invalid byte 8 bit 3 on the CONNECT variable header for willQosBit3 flag",
        )
        val willQos = QualityOfService.fromBooleans(willQosBit4, willQosBit3)
        assertEquals(
            willQos,
            EXACTLY_ONCE,
            "invalid byte 8 qos on the CONNECT variable header for willQos flag",
        )
        val willFlag = connectFlagsPackedInByte.shl(5).shr(7) == 1
        assertFalse(
            willFlag,
            "invalid byte 8 bit 2 on the CONNECT variable header for willFlag flag",
        )
        val cleanStart = connectFlagsPackedInByte.shl(6).shr(7) == 1
        assertFalse(
            cleanStart,
            "invalid byte 8 bit 1 on the CONNECT variable header for cleanSession flag",
        )
        val reserved = connectFlagsPackedInByte.shl(7).shr(7) == 1
        assertFalse(
            reserved,
            "invalid byte 8 bit 0 on the CONNECT variable header for reserved flag",
        )
    }

    @Test
    fun variableHeaderConnectFlagsByte8HasWillFlag() {
        val willPayload = BufferFactory.Default.allocate(1)
        willPayload.writeByte(0x00)
        willPayload.resetForRead()
        val connectionRequest =
            ConnectionRequest(
                clientId = "",
                will = WillConfig.Enabled(TopicName.fromOrThrow("t"), willPayload, AT_MOST_ONCE),
            )
        val buffer = BufferFactory.Default.allocate(packetSizeV4(connectionRequest))
        serializeV4(connectionRequest, buffer)
        buffer.resetForRead()
        buffer.readByte() // skip the first byte
        buffer.readVariableByteInteger() // skip the remaining length
        buffer.readByte() // Length MSB (0)
        buffer.readByte() // Length LSB (4)
        buffer.readByte() // 'M' or 0b01001101
        buffer.readByte() // 'Q' or 0b01010001
        buffer.readByte() // 'T' or 0b01010100
        buffer.readByte() // 'T' or 0b01010100
        buffer.readByte() // 4 or 0b00000100
        val byte = buffer.readUnsignedByte()
        val connectFlagsPackedInByte = byte.toInt()
        val usernameFlag = connectFlagsPackedInByte.shr(7) == 1
        assertFalse(
            usernameFlag,
            "invalid byte 8 bit 7 on the CONNECT variable header for username flag",
        )
        val passwordFlag = connectFlagsPackedInByte.shl(1).shr(7) == 1
        assertFalse(
            passwordFlag,
            "invalid byte 8 bit 6 on the CONNECT variable header for password flag",
        )
        val willRetain = connectFlagsPackedInByte.shl(2).shr(7) == 1
        assertFalse(
            willRetain,
            "invalid byte 8 bit 5 on the CONNECT variable header for willRetain flag",
        )
        val willQosBit4 = connectFlagsPackedInByte.shl(3).shr(7) == 1
        assertFalse(
            willQosBit4,
            "invalid byte 8 bit 4 on the CONNECT variable header for willQosBit4 flag",
        )
        val willQosBit3 = connectFlagsPackedInByte.shl(4).shr(7) == 1
        assertFalse(
            willQosBit3,
            "invalid byte 8 bit 3 on the CONNECT variable header for willQosBit3 flag",
        )
        val willQos = QualityOfService.fromBooleans(willQosBit4, willQosBit3)
        assertEquals(
            willQos,
            AT_MOST_ONCE,
            "invalid byte 8 qos on the CONNECT variable header for willQos flag",
        )
        val willFlag = connectFlagsPackedInByte.shl(5).shr(7) == 1
        assertTrue(
            willFlag,
            "invalid byte 8 bit 2 on the CONNECT variable header for willFlag flag",
        )
        val cleanStart = connectFlagsPackedInByte.shl(6).shr(7) == 1
        assertFalse(
            cleanStart,
            "invalid byte 8 bit 1 on the CONNECT variable header for cleanSession flag",
        )
        val reserved = connectFlagsPackedInByte.shl(7).shr(7) == 1
        assertFalse(
            reserved,
            "invalid byte 8 bit 0 on the CONNECT variable header for reserved flag",
        )
    }

    @Test
    fun variableHeaderConnectFlagsByte8HasCleanStart() {
        val connectionRequest =
            ConnectionRequest(VariableHeader(willQos = AT_MOST_ONCE, cleanSession = true))
        val buffer = BufferFactory.Default.allocate(14)
        serializeV4(connectionRequest, buffer)
        buffer.resetForRead()
        buffer.readByte() // skip the first byte
        buffer.readVariableByteInteger() // skip the remaining length
        buffer.readByte() // Length MSB (0)
        buffer.readByte() // Length LSB (4)
        buffer.readByte() // 'M' or 0b01001101
        buffer.readByte() // 'Q' or 0b01010001
        buffer.readByte() // 'T' or 0b01010100
        buffer.readByte() // 'T' or 0b01010100
        buffer.readByte() // 5 or 0b00000101
        val byte = buffer.readUnsignedByte()
        val connectFlagsPackedInByte = byte.toInt()
        val usernameFlag = connectFlagsPackedInByte.shr(7) == 1
        assertFalse(
            usernameFlag,
            "invalid byte 8 bit 7 on the CONNECT variable header for username flag",
        )
        val passwordFlag = connectFlagsPackedInByte.shl(1).shr(7) == 1
        assertFalse(
            passwordFlag,
            "invalid byte 8 bit 6 on the CONNECT variable header for password flag",
        )
        val willRetain = connectFlagsPackedInByte.shl(2).shr(7) == 1
        assertFalse(
            willRetain,
            "invalid byte 8 bit 5 on the CONNECT variable header for willRetain flag",
        )
        val willQosBit4 = connectFlagsPackedInByte.shl(3).shr(7) == 1
        assertFalse(
            willQosBit4,
            "invalid byte 8 bit 4 on the CONNECT variable header for willQosBit4 flag",
        )
        val willQosBit3 = connectFlagsPackedInByte.shl(4).shr(7) == 1
        assertFalse(
            willQosBit3,
            "invalid byte 8 bit 3 on the CONNECT variable header for willQosBit3 flag",
        )
        val willQos = QualityOfService.fromBooleans(willQosBit4, willQosBit3)
        assertEquals(
            willQos,
            AT_MOST_ONCE,
            "invalid byte 8 qos on the CONNECT variable header for willQos flag",
        )
        val willFlag = connectFlagsPackedInByte.shl(5).shr(7) == 1
        assertFalse(
            willFlag,
            "invalid byte 8 bit 2 on the CONNECT variable header for willFlag flag",
        )
        val cleanStart = connectFlagsPackedInByte.shl(6).shr(7) == 1
        assertTrue(
            cleanStart,
            "invalid byte 8 bit 1 on the CONNECT variable header for cleanSession flag",
        )
        val reserved = connectFlagsPackedInByte.shl(7).shr(7) == 1
        assertFalse(
            reserved,
            "invalid byte 8 bit 0 on the CONNECT variable header for reserved flag",
        )
    }

    @Test
    fun variableHeaderKeepAliveDefault() {
        val connectionRequest = ConnectionRequest()
        val buffer = BufferFactory.Default.allocate(14)
        serializeV4(connectionRequest, buffer)
        buffer.resetForRead()
        buffer.readByte() // skip the first byte
        buffer.readVariableByteInteger() // skip the remaining length
        buffer.readByte() // Length MSB (0)
        buffer.readByte() // Length LSB (4)
        buffer.readByte() // 'M' or 0b01001101
        buffer.readByte() // 'Q' or 0b01010001
        buffer.readByte() // 'T' or 0b01010100
        buffer.readByte() // 'T' or 0b01010100
        buffer.readByte() // 5 or 0b00000101
        buffer.readByte() // connect flags
        val keepAliveSeconds =
            buffer.readUnsignedShort() // read byte 9 and 10 since UShort is 2 Bytes
        assertEquals(keepAliveSeconds, connectionRequest.variableHeader.keepAliveSeconds.toUShort())
        assertEquals(UShort.MAX_VALUE, connectionRequest.variableHeader.keepAliveSeconds.toUShort())
    }

    @Test
    fun variableHeaderKeepAlive0() {
        val connectionRequest = ConnectionRequest(VariableHeader(keepAliveSeconds = 0))
        val buffer = BufferFactory.Default.allocate(14)
        serializeV4(connectionRequest, buffer)
        buffer.resetForRead()
        buffer.readByte() // skip the first byte
        buffer.readVariableByteInteger() // skip the remaining length
        buffer.readByte() // Length MSB (0)
        buffer.readByte() // Length LSB (4)
        buffer.readByte() // 'M' or 0b01001101
        buffer.readByte() // 'Q' or 0b01010001
        buffer.readByte() // 'T' or 0b01010100
        buffer.readByte() // 'T' or 0b01010100
        buffer.readByte() // 5 or 0b00000101
        buffer.readByte() // connect flags
        val keepAliveSeconds =
            buffer.readUnsignedShort() // read byte 9 and 10 since UShort is 2 Bytes
        assertEquals(keepAliveSeconds, connectionRequest.variableHeader.keepAliveSeconds.toUShort())
        assertEquals(0.toUShort(), connectionRequest.variableHeader.keepAliveSeconds.toUShort())
    }

    @Test
    fun variableHeaderKeepAliveMax() {
        val connectionRequest =
            ConnectionRequest(VariableHeader(keepAliveSeconds = UShort.MAX_VALUE.toInt()))
        val buffer = BufferFactory.Default.allocate(14)
        serializeV4(connectionRequest, buffer)
        buffer.resetForRead()
        buffer.readByte() // skip the first byte
        buffer.readVariableByteInteger() // skip the remaining length
        buffer.readByte() // Length MSB (0)
        buffer.readByte() // Length LSB (4)
        buffer.readByte() // 'M' or 0b01001101
        buffer.readByte() // 'Q' or 0b01010001
        buffer.readByte() // 'T' or 0b01010100
        buffer.readByte() // 'T' or 0b01010100
        buffer.readByte() // 5 or 0b00000101
        buffer.readByte() // connect flags
        val keepAliveSeconds =
            buffer.readUnsignedShort() // read byte 9 and 10 since UShort is 2 Bytes
        assertEquals(keepAliveSeconds, connectionRequest.variableHeader.keepAliveSeconds.toUShort())
        assertEquals(UShort.MAX_VALUE, connectionRequest.variableHeader.keepAliveSeconds.toUShort())
    }

    @Test
    fun packetDefault() {
        val request = ConnectionRequest()
        val buffer = BufferFactory.Default.allocate(14)
        serializeV4(request, buffer)
        buffer.resetForRead()
        val requestDeserialized = decodeV4(buffer)
        assertEquals(requestDeserialized, request)
    }

    @Test
    fun packetQos0() {
        val request = ConnectionRequest(VariableHeader(willQos = AT_MOST_ONCE))
        val buffer = BufferFactory.Default.allocate(14)
        serializeV4(request, buffer)
        buffer.resetForRead()
        val requestDeserialized = decodeV4(buffer)
        assertEquals(requestDeserialized, request)
    }

    @Test
    fun usernameFlagMatchesPayloadFailureCaseNoFlagWithUsername() {
        try {
            val connectionRequest =
                ConnectionRequest(
                    VariableHeader(),
                    ConnectionRequest.Payload(userName = "yolo"),
                )
            val warning = connectionRequest.validate()
            if (warning != null) throw warning
            fail()
        } catch (e: MqttWarning) {
        }
    }

    @Test
    fun usernameFlagMatchesPayloadFailureCaseWithFlagNoUsername() {
        try {
            val connectionRequest = ConnectionRequest(VariableHeader(hasUserName = true))
            val warning = connectionRequest.validate()
            if (warning != null) throw warning
            fail()
        } catch (e: MqttWarning) {
        }
    }

    @Test
    fun passwordFlagMatchesPayloadFailureCaseNoFlagWithUsername() {
        try {
            val connectionRequest =
                ConnectionRequest(
                    VariableHeader(),
                    ConnectionRequest.Payload(password = "yolo"),
                )
            val warning = connectionRequest.validate()
            if (warning != null) throw warning
            fail()
        } catch (e: MqttWarning) {
        }
    }

    @Test
    fun passwordFlagMatchesPayloadFailureCaseWithFlagNoUsername() {
        try {
            val connectionRequest = ConnectionRequest(VariableHeader(hasPassword = true))
            val warning = connectionRequest.validate()
            if (warning != null) throw warning
            fail()
        } catch (e: MqttWarning) {
        }
    }

    // ── Impossible state tests: will flag edge cases ────────────────────────

    @Test
    fun willFlagTrueNullWillTopicValidationWarning() {
        val request =
            ConnectionRequest(
                VariableHeader(willFlag = true),
                ConnectionRequest.Payload(clientId = "test"),
            )
        val warning = request.validate()
        assertNotNull(warning, "willFlag=true with null willTopic should produce a warning")
    }

    @Test
    fun willFlagFalseIgnoresWillFieldsInEncoding() {
        val request =
            ConnectionRequest(
                clientId = "test-client",
                keepAliveSeconds = 60,
                cleanSession = true,
            )
        val buffer = BufferFactory.Default.allocate(packetSizeV4(request))
        serializeV4(request, buffer)
        buffer.resetForRead()
        val decoded = decodeV4(buffer) as ConnectionRequest
        assertFalse(decoded.connectFlags.willFlag)
        assertEquals(null, decoded.willTopicString)
        assertEquals(null, decoded.willPayloadValue)
    }

    @Test
    fun willMessageFullRoundTrip() {
        val willPayload = "will-data".encodeToByteArray()
        val willBuf = BufferFactory.Default.allocate(willPayload.size)
        willPayload.forEach { willBuf.writeByte(it) }
        willBuf.resetForRead()
        val request =
            ConnectionRequest(
                clientId = "test-client",
                keepAliveSeconds = 60,
                cleanSession = false,
                will =
                    WillConfig.Enabled(
                        TopicName.fromOrThrow("will/topic"),
                        willBuf,
                        QualityOfService.AT_LEAST_ONCE,
                        retain = true,
                    ),
            )
        assertNotNull(request.validateOrNull(), "valid will message should pass validation")
        val buffer = BufferFactory.Default.allocate(packetSizeV4(request))
        serializeV4(request, buffer)
        buffer.resetForRead()
        val decoded = decodeV4(buffer) as ConnectionRequest
        assertEquals("test-client", decoded.clientId)
        assertTrue(decoded.connectFlags.willFlag)
        assertTrue(decoded.connectFlags.willRetain)
        assertEquals(
            QualityOfService.AT_LEAST_ONCE,
            QualityOfService.fromBooleans(
                (decoded.connectFlags.willQos shr 1) and 1 == 1,
                decoded.connectFlags.willQos and 1 == 1,
            ),
        )
        assertEquals("will/topic", decoded.willTopicString)
    }

    // ── MQTT spec §3.1.2-11: willQos MUST be 0 when willFlag is 0 ──────────

    @Test
    fun willQosWithoutWillFlagValidationWarning() {
        val vh = VariableHeader(willFlag = false, willQos = QualityOfService.AT_LEAST_ONCE)
        val warning = vh.validateOrGetWarning()
        assertNotNull(warning, "willQos != AT_MOST_ONCE with willFlag=false should warn")
    }
}
