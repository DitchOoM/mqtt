package com.ditchoom.mqtt5.controlpacket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.mqtt.MalformedPacketException
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.TopicName
import com.ditchoom.mqtt.controlpacket.WillConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Spec round-trip and edge-case tests for `ControlPacketV5.Connect` (CONNECT, §3.1). Validates:
 *  - empty CONNECT (just clientId), full wire round-trip via the legacy `serialize()` framework
 *  - cleanStart, hasUserName, hasPassword bits round-trip through the connectFlags byte
 *  - rich variable-header properties (`ConnectProperties`) round-trip
 *  - will message: typed `WillConfig.Enabled` round-trips with topic/payload/QoS/retain plus
 *    optional `ConnectWillProperties` (delay, format, content type, response topic, etc.)
 *  - §3.1.2.3 reserved bit must be 0 — rejected at decode
 *  - §3.1.2-12 will QoS = 3 — rejected at decode
 *  - §3.1.2-11 will QoS != 0 with willFlag=0 — rejected at decode
 *  - §3.1.2-13 willRetain = 1 with willFlag=0 — rejected at decode
 *  - dispatcher routes 0x10 → ControlPacketV5.Connect
 */
class V5PacketConnectTests {
    @Test
    fun connectMinimalRoundTrip() {
        val pkt = ConnectionRequest(clientId = "client-7")
        val buf = pkt.serialize()
        assertEquals(0x10.toByte(), buf.readByte()) // type=1, flags=0000
        buf.position(0)
        val decoded = ControlPacketV5.from(buf) as ConnectionRequest
        assertEquals("client-7", decoded.clientIdentifier)
        assertEquals("MQTT", decoded.protocolName)
        assertEquals(5, decoded.protocolVersion)
        assertEquals(false, decoded.cleanStart)
        assertEquals(false, decoded.hasUserName)
        assertEquals(false, decoded.hasPassword)
        assertEquals(WillConfig.Disabled, decoded.will)
    }

    @Test
    fun connectCleanStartRoundTrip() {
        val pkt = ConnectionRequest(clientId = "c", cleanStart = true)
        val buf = pkt.serialize()
        buf.position(0)
        val decoded = ControlPacketV5.from(buf) as ConnectionRequest
        assertTrue(decoded.cleanStart)
    }

    @Test
    fun connectWithCredentialsRoundTrip() {
        val pkt =
            ConnectionRequest(
                clientId = "c",
                userName = "alice",
                password = "secret",
            )
        val buf = pkt.serialize()
        buf.position(0)
        val decoded = ControlPacketV5.from(buf) as ConnectionRequest
        assertTrue(decoded.hasUserName)
        assertTrue(decoded.hasPassword)
        assertEquals("alice", decoded.userName)
        assertEquals("secret", decoded.password)
    }

    @Test
    fun connectWithRichPropertiesRoundTrip() {
        val typed =
            ConnectProperties(
                sessionExpiryIntervalSeconds = 3600uL,
                receiveMaximum = 100,
                maximumPacketSize = 65536uL,
                topicAliasMaximum = 16,
                requestResponseInformation = true,
                requestProblemInformation = false,
                userProperty = listOf("trace" to "abc"),
            )
        val pkt = ConnectionRequest(clientId = "c", props = typed)
        val buf = pkt.serialize()
        buf.position(0)
        val decoded = ControlPacketV5.from(buf) as ConnectionRequest
        val out = decoded.typedProperties
        assertEquals(3600uL, out.sessionExpiryIntervalSeconds)
        assertEquals(100, out.receiveMaximum)
        assertEquals(65536uL, out.maximumPacketSize)
        assertEquals(16, out.topicAliasMaximum)
        assertEquals(true, out.requestResponseInformation)
        assertEquals(false, out.requestProblemInformation)
        assertEquals(listOf("trace" to "abc"), out.userProperty)
    }

    @Test
    fun connectWithWillRoundTrip() {
        val payload =
            BufferFactory.Default.allocate(4).apply {
                writeByte(0x01)
                writeByte(0x02)
                writeByte(0x03)
                writeByte(0x04)
                resetForRead()
            }
        val pkt =
            ConnectionRequest(
                clientId = "c",
                will =
                    WillConfig.Enabled(
                        topic = TopicName.fromOrThrow("will/topic"),
                        payload = payload,
                        qos = QualityOfService.AT_LEAST_ONCE,
                        retain = true,
                    ),
                willProperties =
                    ConnectWillProperties(
                        willDelayIntervalSeconds = 30,
                        payloadFormatIndicator = true,
                        contentType = "text/plain",
                    ),
            )
        val buf = pkt.serialize()
        buf.position(0)
        val decoded = ControlPacketV5.from(buf) as ConnectionRequest
        val will = decoded.will
        assertIs<WillConfig.Enabled>(will)
        assertEquals("will/topic", will.topic.toString())
        assertEquals(QualityOfService.AT_LEAST_ONCE, will.qos)
        assertTrue(will.retain)
        val typedWill = decoded.typedWillProperties
        assertEquals(30L, typedWill?.willDelayIntervalSeconds)
        assertEquals(true, typedWill?.payloadFormatIndicator)
        assertEquals("text/plain", typedWill?.contentType)
    }

    @Test
    fun connectReservedBitRejectedAtDecode() {
        // §3.1.2.3: bit 0 of CONNECT flags is reserved and MUST be 0.
        val buf = BufferFactory.Default.allocate(64)
        buf.writeUByte(0x10u)
        // RL is computed below; minimum CONNECT body
        // protocolName "MQTT"=2+4=6, protocolLevel=1, flags=1, keepAlive=2, propsVbi=1, clientId 0+2=2 → 13
        buf.writeUByte(13u)
        buf.writeUShort(4u) // protocolName length
        buf.writeByte('M'.code.toByte())
        buf.writeByte('Q'.code.toByte())
        buf.writeByte('T'.code.toByte())
        buf.writeByte('T'.code.toByte())
        buf.writeUByte(5u) // protocolLevel
        buf.writeUByte(0x01u) // flags with reserved bit 0 set
        buf.writeUShort(0u) // keepAlive
        buf.writeUByte(0u) // properties length
        buf.writeUShort(0u) // empty clientId
        buf.resetForRead()
        assertFailsWith<MalformedPacketException> { ControlPacketV5.from(buf) }
    }

    @Test
    fun connectWillQos3RejectedAtConstruction() {
        // §3.1.2-12: Will QoS = 3 is malformed. Init validation rejects construction.
        assertFailsWith<MalformedPacketException> {
            ControlPacketV5.Connect(
                protocolName = "MQTT",
                protocolLevel = 5u,
                connectFlags = ConnectFlagsV5(0x1Cu), // willFlag=1, willQos=3
                keepAlive = 0u,
                properties = emptyList(),
                clientId = "",
                willProperties = emptyList(),
                willTopicString = "",
                willPayloadValue = null,
            )
        }
    }

    @Test
    fun connectWillQosNonZeroWithoutWillFlagRejected() {
        // §3.1.2-11: willFlag=0 → willQos MUST be 0
        val buf = BufferFactory.Default.allocate(64)
        buf.writeUByte(0x10u)
        buf.writeUByte(13u)
        buf.writeUShort(4u)
        buf.writeByte('M'.code.toByte())
        buf.writeByte('Q'.code.toByte())
        buf.writeByte('T'.code.toByte())
        buf.writeByte('T'.code.toByte())
        buf.writeUByte(5u)
        buf.writeUByte(0x08u) // willFlag=0, willQos=1 (bit 3 set)
        buf.writeUShort(0u)
        buf.writeUByte(0u)
        buf.writeUShort(0u)
        buf.resetForRead()
        assertFailsWith<MalformedPacketException> { ControlPacketV5.from(buf) }
    }

    @Test
    fun connectWillRetainWithoutWillFlagRejected() {
        // §3.1.2-13: willFlag=0 → willRetain MUST be 0
        val buf = BufferFactory.Default.allocate(64)
        buf.writeUByte(0x10u)
        buf.writeUByte(13u)
        buf.writeUShort(4u)
        buf.writeByte('M'.code.toByte())
        buf.writeByte('Q'.code.toByte())
        buf.writeByte('T'.code.toByte())
        buf.writeByte('T'.code.toByte())
        buf.writeUByte(5u)
        buf.writeUByte(0x20u) // willFlag=0, willRetain=1
        buf.writeUShort(0u)
        buf.writeUByte(0u)
        buf.writeUShort(0u)
        buf.resetForRead()
        assertFailsWith<MalformedPacketException> { ControlPacketV5.from(buf) }
    }

    @Test
    fun connectDispatchedThroughControlPacketV5() {
        val pkt = ConnectionRequest(clientId = "dispatched")
        val buf = pkt.serialize()
        val decoded = ControlPacketV5.from(buf)
        assertIs<ControlPacketV5.Connect>(decoded)
        assertEquals(1.toByte(), decoded.controlPacketValue)
    }

    @Test
    fun connectDefaultsAreSpecCompliant() {
        // Default ConnectionRequest() should yield: MQTT v5, no will, no creds, no cleanStart.
        val pkt = ConnectionRequest()
        assertEquals("MQTT", pkt.protocolName)
        assertEquals(5, pkt.protocolVersion)
        assertEquals(false, pkt.cleanStart)
        assertEquals(false, pkt.hasUserName)
        assertEquals(false, pkt.hasPassword)
        assertEquals(WillConfig.Disabled, pkt.will)
        assertNull(pkt.userName)
        assertNull(pkt.password)
    }

    @Test
    fun connectWithUserPropertyRoundTrip() {
        val pkt =
            ConnectionRequest(
                clientId = "c",
                props =
                    ConnectProperties(
                        userProperty = listOf("k1" to "v1", "k2" to "v2"),
                    ),
            )
        val buf = pkt.serialize()
        buf.position(0)
        val decoded = ControlPacketV5.from(buf) as ConnectionRequest
        assertEquals(listOf("k1" to "v1", "k2" to "v2"), decoded.typedProperties.userProperty)
    }
}
