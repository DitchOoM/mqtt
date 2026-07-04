package com.ditchoom.mqtt5.controlpacket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.mqtt.MalformedPacketException
import com.ditchoom.mqtt.controlpacket.format.ReasonCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Spec-edge-case round-trip tests for the six ack-shaped packets migrated to `ControlPacketV5`:
 * PUBACK, PUBREC, PUBREL, PUBCOMP, DISCONNECT, AUTH. Plus PINGREQ / PINGRESP.
 *
 * For each ack-shaped packet, three wire-format branches are validated:
 *  (a) shortest legal encoding — packet identifier only (Disconnect/Auth: empty body)
 *  (b) packet identifier + reason code, with property length=0 (matches legacy wire format)
 *  (c) packet identifier + reason code + non-empty property bag
 *
 * Each branch round-trips via the legacy `ControlPacket.serialize()` → `decodeV5()`
 * pipeline (full wire format including VBI). Spec-invalid reason codes are rejected at
 * construction time. PUBREL also asserts the reserved low-nibble bits are `0010`.
 */
class V5PacketAckShapedTests {
    // ── PINGREQ / PINGRESP ──────────────────────────────────────────────────

    @Test
    fun pingReqRoundTripFullWire() {
        val buf = PingRequest().serialize()
        assertEquals(0xC0.toByte(), buf.readByte()) // type=12, flags=0000
        assertEquals(0x00.toByte(), buf.readByte()) // RL=0
        buf.position(0)
        assertEquals(PingRequest(), decodeV5(buf))
    }

    @Test
    fun pingRespRoundTripFullWire() {
        val buf = PingResponse().serialize()
        assertEquals(0xD0.toByte(), buf.readByte()) // type=13, flags=0000
        assertEquals(0x00.toByte(), buf.readByte()) // RL=0
        buf.position(0)
        assertEquals(PingResponse(), decodeV5(buf))
    }

    @Test
    fun pingReqWithNonZeroRemainingLengthRejected() {
        val buf = BufferFactory.Default.allocate(3)
        buf.writeUByte(0xC0u)
        buf.writeUByte(0x01u)
        buf.writeUByte(0x00u)
        buf.resetForRead()
        assertFailsWith<MalformedPacketException> { decodeV5(buf) }
    }

    // ── PUBACK ──────────────────────────────────────────────────────────────

    @Test
    fun pubAckShortestEncodingRoundTrip() {
        // SUCCESS + no properties → omit reason code and property length entirely. RL=2.
        val pkt = PublishAcknowledgment(packetIdentifier = 7)
        val buf = pkt.serialize()
        assertEquals(0x40.toByte(), buf.readByte())
        assertEquals(0x02.toByte(), buf.readByte()) // RL=2
        buf.position(0)
        val decoded = decodeV5(buf)
        assertIs<PublishAcknowledgment>(decoded)
        assertEquals(7, decoded.packetIdentifier)
        assertNull(decoded.reasonCode)
        assertNull(decoded.properties)
    }

    @Test
    fun pubAckReasonCodeOnlyRoundTrip() {
        // Non-success reason code, no properties → reasonCode + property-length=0. RL=4.
        val pkt = PublishAcknowledgment(7, ReasonCode.NO_MATCHING_SUBSCRIBERS)
        val buf = pkt.serialize()
        assertEquals(0x40.toByte(), buf.readByte())
        assertEquals(0x04.toByte(), buf.readByte()) // RL=4
        assertEquals(0x00.toByte(), buf.readByte())
        assertEquals(0x07.toByte(), buf.readByte()) // packet id lo
        assertEquals(0x10.toByte(), buf.readByte()) // NO_MATCHING_SUBSCRIBERS
        assertEquals(0x00.toByte(), buf.readByte()) // property length VBI = 0
        buf.position(0)
        val decoded = decodeV5(buf)
        assertIs<PublishAcknowledgment>(decoded)
        assertEquals(7, decoded.packetIdentifier)
        assertEquals(ReasonCode.NO_MATCHING_SUBSCRIBERS.byte, decoded.reasonCode)
    }

    @Test
    fun pubAckWithPropertiesRoundTrip() {
        val pkt =
            PublishAcknowledgment(
                packetIdentifier = 99,
                reasonCode = ReasonCode.QUOTA_EXCEEDED,
                reasonString = "rate limited",
                userProperty = listOf("retry-after" to "60"),
            )
        val buf = pkt.serialize()
        val decoded = decodeV5(buf)
        assertIs<PublishAcknowledgment>(decoded)
        assertEquals(99, decoded.packetIdentifier)
        assertEquals(ReasonCode.QUOTA_EXCEEDED.byte, decoded.reasonCode)
        assertEquals("rate limited", decoded.properties.reasonStringValue())
        assertEquals(listOf("retry-after" to "60"), decoded.properties.userProperties())
    }

    @Test
    fun pubAckInvalidReasonCodeRejected() {
        // RECEIVE_MAXIMUM_EXCEEDED is not in PUBACK's spec table.
        assertFailsWith<IllegalArgumentException> {
            PublishAcknowledgment(1, ReasonCode.RECEIVE_MAXIMUM_EXCEEDED)
        }
    }

    // ── PUBREC ──────────────────────────────────────────────────────────────

    @Test
    fun pubRecShortestEncodingRoundTrip() {
        val pkt = PublishReceived(packetIdentifier = 7)
        val buf = pkt.serialize()
        assertEquals(0x50.toByte(), buf.readByte()) // type=5, flags=0000
        assertEquals(0x02.toByte(), buf.readByte()) // RL=2
        buf.position(0)
        assertEquals(pkt, decodeV5(buf))
    }

    @Test
    fun pubRecInvalidReasonCodeRejected() {
        assertFailsWith<IllegalArgumentException> {
            PublishReceived(1, ReasonCode.RECEIVE_MAXIMUM_EXCEEDED)
        }
    }

    // ── PUBREL ──────────────────────────────────────────────────────────────

    @Test
    fun pubRelShortestEncodingRoundTrip() {
        // PUBREL has reserved low-nibble bits 0010 (wire byte 0x62).
        val pkt = PublishRelease(packetIdentifier = 7)
        val buf = pkt.serialize()
        assertEquals(0x62.toByte(), buf.readByte())
        assertEquals(0x02.toByte(), buf.readByte()) // RL=2
        buf.position(0)
        assertEquals(pkt, decodeV5(buf))
    }

    @Test
    fun pubRelReasonCodeOnlyRoundTrip() {
        val pkt = PublishRelease(7, ReasonCode.PACKET_IDENTIFIER_NOT_FOUND)
        val buf = pkt.serialize()
        assertEquals(0x62.toByte(), buf.readByte())
        buf.position(0)
        val decoded = decodeV5(buf)
        assertIs<PublishRelease>(decoded)
        assertEquals(7, decoded.packetIdentifier)
        assertEquals(ReasonCode.PACKET_IDENTIFIER_NOT_FOUND.byte, decoded.reasonCode)
    }

    @Test
    fun pubRelInvalidReasonCodeRejected() {
        // NO_MATCHING_SUBSCRIBERS is in PUBACK's table but not PUBREL's.
        assertFailsWith<IllegalArgumentException> {
            PublishRelease(1, ReasonCode.NO_MATCHING_SUBSCRIBERS)
        }
    }

    @Test
    fun pubRelWithInvalidReservedFlagsRejected() {
        // Spec §3.6.1: reserved low-nibble bits MUST be 0010. Try 0011 (extra retain bit).
        val buf = BufferFactory.Default.allocate(4)
        buf.writeUByte(0x63u) // bad flags
        buf.writeUByte(0x02u)
        buf.writeUShort(7u)
        buf.resetForRead()
        assertFailsWith<MalformedPacketException> { decodeV5(buf) }
    }

    // ── PUBCOMP ─────────────────────────────────────────────────────────────

    @Test
    fun pubCompShortestEncodingRoundTrip() {
        val pkt = PublishComplete(packetIdentifier = 7.toUShort())
        val buf = pkt.serialize()
        assertEquals(0x70.toByte(), buf.readByte())
        assertEquals(0x02.toByte(), buf.readByte()) // RL=2
        buf.position(0)
        assertEquals(pkt, decodeV5(buf))
    }

    @Test
    fun pubCompInvalidReasonCodeRejected() {
        assertFailsWith<IllegalArgumentException> {
            PublishComplete(1, ReasonCode.NO_MATCHING_SUBSCRIBERS)
        }
    }

    // ── DISCONNECT ──────────────────────────────────────────────────────────

    @Test
    fun disconnectNormalShortestEncodingRoundTrip() {
        // NORMAL_DISCONNECTION with no properties → empty body. RL=0.
        val pkt = DisconnectNotification()
        val buf = pkt.serialize()
        assertEquals(0xE0.toByte(), buf.readByte())
        assertEquals(0x00.toByte(), buf.readByte()) // RL=0
        buf.position(0)
        val decoded = decodeV5(buf)
        assertIs<DisconnectNotification>(decoded)
        assertNull(decoded.reasonCode)
        assertNull(decoded.properties)
    }

    @Test
    fun disconnectReasonCodeOnlyRoundTrip() {
        // Non-default reason code, no properties → reasonCode + property-length=0. RL=2.
        val pkt = DisconnectNotification(reasonCode = ReasonCode.UNSPECIFIED_ERROR)
        val buf = pkt.serialize()
        assertEquals(0xE0.toByte(), buf.readByte())
        assertEquals(0x02.toByte(), buf.readByte()) // RL=2
        assertEquals(0x80.toByte(), buf.readByte()) // UNSPECIFIED_ERROR
        assertEquals(0x00.toByte(), buf.readByte()) // property length VBI = 0
        buf.position(0)
        val decoded = decodeV5(buf)
        assertIs<DisconnectNotification>(decoded)
        assertEquals(ReasonCode.UNSPECIFIED_ERROR.byte, decoded.reasonCode)
    }

    @Test
    fun disconnectWithPropertiesRoundTrip() {
        val pkt =
            DisconnectNotification(
                reasonCode = ReasonCode.SERVER_SHUTTING_DOWN,
                sessionExpiryIntervalSeconds = 60u,
                reasonString = "graceful shutdown",
                userProperty = listOf("admin" to "alice"),
                serverReference = "mqtt://backup.example",
            )
        val buf = pkt.serialize()
        val decoded = decodeV5(buf)
        assertIs<DisconnectNotification>(decoded)
        assertEquals(ReasonCode.SERVER_SHUTTING_DOWN.byte, decoded.reasonCode)
        assertEquals("graceful shutdown", decoded.properties.reasonStringValue())
        assertEquals(listOf("admin" to "alice"), decoded.properties.userProperties())
    }

    @Test
    fun disconnectRl1OmitsPropertyLength() {
        // Spec §3.14.2.2: when RL < 4 there is no Property Length. RL=1: just reason code.
        val buf = BufferFactory.Default.allocate(3)
        buf.writeUByte(0xE0u)
        buf.writeUByte(0x01u)
        buf.writeUByte(0x04u) // DISCONNECT_WITH_WILL_MESSAGE
        buf.position(0)
        val decoded = decodeV5(buf)
        assertIs<DisconnectNotification>(decoded)
        assertEquals(ReasonCode.DISCONNECT_WITH_WILL_MESSAGE.byte, decoded.reasonCode)
        assertNull(decoded.properties)
    }

    @Test
    fun disconnectInvalidReasonCodeRejected() {
        // NO_MATCHING_SUBSCRIBERS (0x10) is valid in PUBACK/PUBREC but not in DISCONNECT.
        // Note: GRANTED_QOS_0 shares byte 0x00 with NORMAL_DISCONNECTION so cannot be used
        // as a "bad" sample — validation is byte-based, not enum-typed.
        assertFailsWith<IllegalArgumentException> {
            DisconnectNotification(reasonCode = ReasonCode.NO_MATCHING_SUBSCRIBERS)
        }
    }

    // ── AUTH ────────────────────────────────────────────────────────────────

    @Test
    fun authShortestEncodingRoundTrip() {
        // SUCCESS, no properties → empty body. RL=0.
        val pkt = AuthenticationExchange()
        val buf = pkt.serialize()
        assertEquals(0xF0.toByte(), buf.readByte())
        assertEquals(0x00.toByte(), buf.readByte()) // RL=0
        buf.position(0)
        val decoded = decodeV5(buf)
        assertIs<AuthenticationExchange>(decoded)
        assertNull(decoded.reasonCode)
    }

    @Test
    fun authContinueAuthenticationRoundTrip() {
        val pkt = AuthenticationExchange(reasonCode = ReasonCode.CONTINUE_AUTHENTICATION)
        val buf = pkt.serialize()
        assertEquals(0xF0.toByte(), buf.readByte())
        assertEquals(0x02.toByte(), buf.readByte()) // RL=2
        assertEquals(0x18.toByte(), buf.readByte()) // CONTINUE_AUTHENTICATION
        assertEquals(0x00.toByte(), buf.readByte()) // property length VBI = 0
        buf.position(0)
        val decoded = decodeV5(buf)
        assertIs<AuthenticationExchange>(decoded)
        assertEquals(ReasonCode.CONTINUE_AUTHENTICATION.byte, decoded.reasonCode)
    }

    @Test
    fun authReauthenticateRoundTrip() {
        val pkt = AuthenticationExchange(reasonCode = ReasonCode.REAUTHENTICATE)
        val buf = pkt.serialize()
        val decoded = decodeV5(buf)
        assertIs<AuthenticationExchange>(decoded)
        assertEquals(ReasonCode.REAUTHENTICATE.byte, decoded.reasonCode)
    }

    @Test
    fun authInvalidReasonCodeRejected() {
        // NORMAL_DISCONNECTION shares byte 0x00 with SUCCESS, but NOT_AUTHORIZED is non-spec for AUTH.
        assertFailsWith<IllegalArgumentException> {
            AuthenticationExchange(reasonCode = ReasonCode.NOT_AUTHORIZED)
        }
    }

    // ── Cross-cutting: invalid wire reason code byte rejected at decode ────

    @Test
    fun pubAckWireWithInvalidReasonCodeRejectedAtDecode() {
        // 0x99 (PAYLOAD_FORMAT_INVALID) IS valid for PUBACK. 0x9C (USE_ANOTHER_SERVER) is NOT.
        val buf = BufferFactory.Default.allocate(6)
        buf.writeUByte(0x40u)
        buf.writeUByte(0x04u)
        buf.writeUShort(1u)
        buf.writeUByte(0x9Cu) // not in PUBACK valid set
        buf.writeUByte(0x00u) // property length 0
        buf.resetForRead()
        assertFailsWith<IllegalArgumentException> { decodeV5(buf) }
    }
}
