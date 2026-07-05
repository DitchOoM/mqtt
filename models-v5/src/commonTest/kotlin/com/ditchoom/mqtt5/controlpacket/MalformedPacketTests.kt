package com.ditchoom.mqtt5.controlpacket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.mqtt.MalformedPacketException
import com.ditchoom.mqtt.controlpacket.format.ReasonCode
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Tests that the parser correctly rejects malformed or truncated MQTT 5.0 packets.
 *
 * References: MQTT 5.0 §4.13 Handling errors
 */
class MalformedPacketTests {
    private fun rawBuffer(vararg bytes: Int) =
        BufferFactory.Default.allocate(bytes.size).also { buf ->
            bytes.forEach { buf.writeByte(it.toByte()) }
            buf.resetForRead()
        }

    // ── Reserved packet type (§2.1.2) ───────────────────────────────────────

    @Test
    fun reservedPacketType0Throws() {
        // Packet type 0 is reserved — MUST be treated as malformed
        val buffer = rawBuffer(0x00, 0x00)
        assertFailsWith<MalformedPacketException> {
            decodeV5(buffer)
        }
    }

    // ── Truncated remaining length VBI ──────────────────────────────────────

    @Test
    fun truncatedVbiThrows() {
        // PUBLISH header with continuation bit set but no more bytes
        val buffer = rawBuffer(0x30, 0x80) // 0x80 has continuation bit set
        assertFailsWith<Throwable> {
            decodeV5(buffer)
        }
    }

    // ── Remaining length exceeds actual data ────────────────────────────────

    @Test
    fun remainingLengthExceedsAvailableDataThrows() {
        // PUBLISH with remaining length = 32 but only 2 bytes of actual body
        val buffer = rawBuffer(0x30, 0x20, 0x00, 0x01)
        assertFailsWith<Throwable> {
            decodeV5(buffer)
        }
    }

    // ── ACK packets with invalid remaining lengths ──────────────────────────

    @Test
    fun pubackRemainingLength0Throws() {
        // PUBACK needs at least 2 bytes for packet identifier
        val buffer = rawBuffer(0x40, 0x00)
        assertFailsWith<Throwable> {
            decodeV5(buffer)
        }
    }

    @Test
    fun pubackRemainingLength1Throws() {
        // PUBACK packet ID requires 2 bytes; 1 byte is invalid
        val buffer = rawBuffer(0x40, 0x01, 0x00)
        assertFailsWith<Throwable> {
            decodeV5(buffer)
        }
    }

    // ── PUBLISH with both QoS bits set (§3.3.1.2) ──────────────────────────

    @Test
    fun publishQosBothBitsSetThrowsMalformed() {
        // QoS bits 11 (value 3) is invalid per MQTT-3.3.1-4
        // byte1: type=3(0011), flags=0110 → 0x36
        val buffer = rawBuffer(0x36, 0x04, 0x00, 0x01, 0x61, 0x00)
        assertFailsWith<MalformedPacketException> {
            decodeV5(buffer)
        }
    }

    // ── Invalid reason codes ────────────────────────────────────────────────

    @Test
    fun pubackInvalidReasonCodeThrows() {
        assertFailsWith<IllegalArgumentException> {
            PublishAcknowledgment(1, ReasonCode.RECEIVE_MAXIMUM_EXCEEDED)
        }
    }

    @Test
    fun pubrecInvalidReasonCodeThrows() {
        assertFailsWith<IllegalArgumentException> {
            PublishReceived(1, ReasonCode.RECEIVE_MAXIMUM_EXCEEDED)
        }
    }

    @Test
    fun pubrelInvalidReasonCodeThrows() {
        assertFailsWith<IllegalArgumentException> {
            PublishRelease(1, ReasonCode.RECEIVE_MAXIMUM_EXCEEDED)
        }
    }

    @Test
    fun pubcompInvalidReasonCodeThrows() {
        assertFailsWith<IllegalArgumentException> {
            PublishComplete(1, ReasonCode.RECEIVE_MAXIMUM_EXCEEDED)
        }
    }

    // ── CONNECT with invalid reserved flag (§3.1.2.3) ───────────────────────

    @Test
    fun connectReservedFlagSetThrows() {
        // Construct a CONNECT where the reserved bit (bit 0) in connect flags is set
        val buffer =
            rawBuffer(
                0x10,
                0x0D, // CONNECT, RL=13
                0x00,
                0x04,
                0x4D,
                0x51,
                0x54,
                0x54, // "MQTT"
                0x05, // protocol level 5
                0x01, // connect flags: reserved bit set (bit 0 = 1) — INVALID
                0x00,
                0x00, // keep alive
                0x00, // props length
                0x00,
                0x00, // client ID
            )
        assertFailsWith<MalformedPacketException> {
            decodeV5(buffer)
        }
    }

    // ── CONNECT with willQos > 0 but willFlag = false (§3.1.2.11) ──────────

    @Test
    fun connectWillQosNonZeroWithoutWillFlagThrows() {
        // Connect flags: willFlag=0 but willQos=1 (bits 4-3 = 01)
        // Flags byte: 0b0000_1000 = 0x08
        val buffer =
            rawBuffer(
                0x10,
                0x0D,
                0x00,
                0x04,
                0x4D,
                0x51,
                0x54,
                0x54,
                0x05,
                0x08, // willQos=1 but willFlag=0 — violates MQTT-3.1.2-11
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
            )
        assertFailsWith<MalformedPacketException> {
            decodeV5(buffer)
        }
    }
}
