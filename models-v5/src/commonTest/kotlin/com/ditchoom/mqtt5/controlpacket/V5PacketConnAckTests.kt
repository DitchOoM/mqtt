package com.ditchoom.mqtt5.controlpacket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.mqtt.MalformedPacketException
import com.ditchoom.mqtt.ProtocolError
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.format.ReasonCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Spec round-trip and edge-case tests for `ControlPacketV5.ConnAck` (CONNACK, §3.2). Validates:
 *  - the shortest legal encoding (sessionPresent=false, SUCCESS, no properties)
 *  - sessionPresent flag round-trips through the acknowledgeFlags byte
 *  - typed [ConnAckProperties] round-trips for each rich property field
 *  - [§3.2.2-6] non-success reason code MUST have sessionPresent=0 (rejected at construction)
 *  - acknowledgeFlags reserved bits 1-7 MUST be 0 (rejected at construction and decode)
 *  - invalid CONNACK reason codes rejected at construction and decode
 *  - ProtocolError on receiveMaximum=0 / maximumPacketSize=0 in decoded properties
 */
class V5PacketConnAckTests {
    @Test
    fun connackSuccessShortestRoundTrip() {
        val pkt = ConnectionAcknowledgment()
        val buf = pkt.serialize()
        assertEquals(0x20.toByte(), buf.readByte()) // type=2, no flags
        assertEquals(0x03.toByte(), buf.readByte()) // RL=3
        assertEquals(0x00.toByte(), buf.readByte()) // sessionPresent=0
        assertEquals(0x00.toByte(), buf.readByte()) // SUCCESS
        assertEquals(0x00.toByte(), buf.readByte()) // property length VBI=0
        buf.position(0)
        val decoded = ControlPacketV5.from(buf) as ConnectionAcknowledgment
        assertFalse(decoded.sessionPresent)
        assertEquals(ReasonCode.SUCCESS, decoded.connectReason)
        assertTrue(decoded.properties.isEmpty())
    }

    @Test
    fun connackSessionPresentRoundTrip() {
        val pkt = ConnectionAcknowledgment(sessionPresent = true)
        val buf = pkt.serialize()
        buf.position(0)
        val decoded = ControlPacketV5.from(buf) as ConnectionAcknowledgment
        assertTrue(decoded.sessionPresent)
        assertEquals(ReasonCode.SUCCESS, decoded.connectReason)
    }

    @Test
    fun connackNonSuccessReasonRoundTrip() {
        val pkt = ConnectionAcknowledgment(connectReason = ReasonCode.NOT_AUTHORIZED)
        val buf = pkt.serialize()
        buf.position(0)
        val decoded = ControlPacketV5.from(buf) as ConnectionAcknowledgment
        assertFalse(decoded.sessionPresent)
        assertEquals(ReasonCode.NOT_AUTHORIZED, decoded.connectReason)
        assertFalse(decoded.isSuccessful)
    }

    @Test
    fun connackWithRichPropertiesRoundTrip() {
        val typed = ConnAckProperties(
            sessionExpiryIntervalSeconds = 300uL,
            receiveMaximum = 100,
            maximumQos = QualityOfService.AT_LEAST_ONCE,
            retainAvailable = false,
            maximumPacketSize = 1024uL,
            assignedClientIdentifier = "client-99",
            topicAliasMaximum = 16,
            reasonString = "ok",
            userProperty = listOf("k" to "v"),
            supportsWildcardSubscriptions = false,
            subscriptionIdentifiersAvailable = false,
            sharedSubscriptionAvailable = false,
            serverKeepAlive = 60,
            responseInformation = "/response/topic",
            serverReference = "mqtt://other.example",
        )
        val pkt = ConnectionAcknowledgment(properties = typed)
        val buf = pkt.serialize()
        buf.position(0)
        val decoded = ControlPacketV5.from(buf) as ConnectionAcknowledgment
        val out = decoded.typedProperties
        assertEquals(300uL, out.sessionExpiryIntervalSeconds)
        assertEquals(100, out.receiveMaximum)
        assertEquals(QualityOfService.AT_LEAST_ONCE, out.maximumQos)
        assertFalse(out.retainAvailable)
        assertEquals(1024uL, out.maximumPacketSize)
        assertEquals("client-99", out.assignedClientIdentifier)
        assertEquals(16, out.topicAliasMaximum)
        assertEquals("ok", out.reasonString)
        assertEquals(listOf("k" to "v"), out.userProperty)
        assertFalse(out.supportsWildcardSubscriptions)
        assertFalse(out.subscriptionIdentifiersAvailable)
        assertFalse(out.sharedSubscriptionAvailable)
        assertEquals(60, out.serverKeepAlive)
        assertEquals("/response/topic", out.responseInformation)
        assertEquals("mqtt://other.example", out.serverReference)
    }

    @Test
    fun connackNonSuccessWithSessionPresentRejected() {
        // §3.2.2-6: non-success reason code MUST have sessionPresent=0.
        assertFailsWith<IllegalArgumentException> {
            ConnectionAcknowledgment(sessionPresent = true, connectReason = ReasonCode.UNSPECIFIED_ERROR)
        }
    }

    @Test
    fun connackReservedFlagBitsRejectedAtConstruction() {
        // Acknowledge flags reserved bits 1-7 MUST be 0.
        assertFailsWith<IllegalArgumentException> {
            ControlPacketV5.ConnAck(
                acknowledgeFlags = 0x02u,
                connectReasonCode = ReasonCode.SUCCESS.byte,
                properties = emptyList(),
            )
        }
    }

    @Test
    fun connackReservedFlagBitsRejectedAtDecode() {
        val buf = BufferFactory.Default.allocate(5)
        buf.writeUByte(0x20u)
        buf.writeUByte(0x03u)
        buf.writeUByte(0x80u) // reserved bit 7 set
        buf.writeUByte(0x00u)
        buf.writeUByte(0x00u)
        buf.resetForRead()
        assertFailsWith<IllegalArgumentException> { ControlPacketV5.from(buf) }
    }

    @Test
    fun connackInvalidReasonCodeRejected() {
        // GRANTED_QOS_2 (0x02) is not in CONNACK's valid reason code set.
        assertFailsWith<IllegalArgumentException> {
            ControlPacketV5.ConnAck(
                acknowledgeFlags = 0x00u,
                connectReasonCode = 0x02u,
                properties = emptyList(),
            )
        }
    }

    @Test
    fun connackInvalidReasonCodeOnWireRejectedAtDecode() {
        val buf = BufferFactory.Default.allocate(5)
        buf.writeUByte(0x20u)
        buf.writeUByte(0x03u)
        buf.writeUByte(0x00u)
        buf.writeUByte(0x10u) // not in CONNACK valid set
        buf.writeUByte(0x00u)
        buf.resetForRead()
        assertFailsWith<IllegalArgumentException> { ControlPacketV5.from(buf) }
    }

    @Test
    fun connackReceiveMaximumZeroRejectedOnDecode() {
        // §3.2.2.3.3: Receive Maximum value 0 is a Protocol Error.
        val buf = BufferFactory.Default.allocate(8)
        buf.writeUByte(0x20u)
        buf.writeUByte(0x06u) // RL=6
        buf.writeUByte(0x00u)
        buf.writeUByte(0x00u) // SUCCESS
        buf.writeUByte(0x03u) // props len = 3
        buf.writeUByte(0x21u) // ReceiveMaximum prop id
        buf.writeUShort(0u) // value = 0
        buf.resetForRead()
        val packet = ControlPacketV5.from(buf) as ConnectionAcknowledgment
        // Decoding the packet succeeds; ProtocolError fires on the typed-accessor.
        assertFailsWith<ProtocolError> { packet.typedProperties }
    }

    @Test
    fun connackMaximumPacketSizeZeroRejectedOnDecode() {
        // §3.2.2.3.6: Maximum Packet Size = 0 is a Protocol Error.
        val buf = BufferFactory.Default.allocate(10)
        buf.writeUByte(0x20u)
        buf.writeUByte(0x08u)
        buf.writeUByte(0x00u)
        buf.writeUByte(0x00u)
        buf.writeUByte(0x05u) // props len = 5
        buf.writeUByte(0x27u) // MaximumPacketSize id
        buf.writeUInt(0u) // value 0
        buf.resetForRead()
        val packet = ControlPacketV5.from(buf) as ConnectionAcknowledgment
        assertFailsWith<ProtocolError> { packet.typedProperties }
    }

    @Test
    fun connackTypedAccessorsDelegateToProperties() {
        val pkt = ConnectionAcknowledgment(
            properties = ConnAckProperties(
                sessionExpiryIntervalSeconds = 60uL,
                receiveMaximum = 50,
                maximumPacketSize = 2048uL,
                assignedClientIdentifier = "id",
                serverKeepAlive = 30,
            ),
        )
        // IConnectionAcknowledgment interface accessors all delegate to typedProperties.
        assertEquals(60uL, pkt.sessionExpiryInterval)
        assertEquals(50, pkt.receiveMaximum)
        assertEquals(2048uL, pkt.maxPacketSize)
        assertEquals("id", pkt.assignedClientIdentifier)
        assertEquals(30, pkt.serverKeepAlive)
    }

    @Test
    fun connackDefaultsForOmittedProperties() {
        // No properties → typedProperties returns spec defaults.
        val pkt = ConnectionAcknowledgment()
        val typed = pkt.typedProperties
        assertEquals(UShort.MAX_VALUE.toInt(), typed.receiveMaximum) // §3.2.2.3.3 default
        assertEquals(QualityOfService.EXACTLY_ONCE, typed.maximumQos) // §3.2.2.3.4 default
        assertTrue(typed.retainAvailable) // §3.2.2.3.5 default
        assertTrue(typed.supportsWildcardSubscriptions)
        assertTrue(typed.subscriptionIdentifiersAvailable)
        assertTrue(typed.sharedSubscriptionAvailable)
    }

    @Test
    fun connackUnknownPropertyIdRejected() {
        // An unknown property id in the bag must fail decode (the generated MqttPropertyCodec
        // throws IllegalArgumentException for a discriminator that isn't in any @PacketType).
        val buf = BufferFactory.Default.allocate(8)
        buf.writeUByte(0x20u)
        buf.writeUByte(0x06u)
        buf.writeUByte(0x00u)
        buf.writeUByte(0x00u)
        buf.writeUByte(0x03u) // props len = 3
        buf.writeUByte(0xFEu) // unknown property id
        buf.writeUShort(0u)
        buf.resetForRead()
        assertFailsWith<IllegalArgumentException> { ControlPacketV5.from(buf) }
    }
}
