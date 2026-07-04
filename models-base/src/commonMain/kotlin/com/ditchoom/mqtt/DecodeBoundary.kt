package com.ditchoom.mqtt

import com.ditchoom.buffer.codec.DecodeException

/**
 * Classifies [e] as a *malformed wire bytes* failure — one of the exception families a
 * spec-malformed or truncated MQTT frame naturally throws while being decoded, as opposed to a
 * genuine decoder **bug**.
 *
 * The set is deliberately the same one the fuzzers have validated over hundreds of thousands of
 * iterations (`isAcceptedDecodeFailure` in the fuzz support files):
 *
 * - [DecodeException] — the generated codecs' native "malformed wire" signal (unknown packet
 *   type, body overrun, bad length prefix).
 * - [IllegalArgumentException] — variant `init` / `require` validation of a decoded value (e.g.
 *   an invalid reason code or flag combination). This is the widest net; it is included because
 *   at the decode boundary an IAE overwhelmingly means "the peer sent an out-of-range value,"
 *   and the fuzzers treat it as such.
 * - The name-matched buffer/charset families — truncation hits the platform buffer's underflow
 *   class (no common supertype across targets), and malformed UTF-8 in a length-prefixed string
 *   hits the charset decoder's `CharacterCodingException` subclasses.
 * - The Kotlin/JS runtime analogues, whose `::class.simpleName` is null, matched by message.
 *
 * Genuine decoder bugs — `NullPointerException`, `IllegalStateException`, `ClassCastException`,
 * `CancellationException`, `OutOfMemoryError`, etc. — are intentionally NOT matched, so
 * [mappingMalformedWire] rethrows them unwrapped instead of masking them as broker garbage.
 */
fun isMalformedWireException(e: Throwable): Boolean =
    e is DecodeException ||
        e is IllegalArgumentException ||
        e::class.simpleName in malformedWireBufferExceptionNames ||
        isJsRuntimeMalformedWire(e)

private val malformedWireBufferExceptionNames =
    setOf(
        "BufferUnderflowException",
        "IndexOutOfBoundsException",
        "ArrayIndexOutOfBoundsException",
        "MalformedInputException",
        "UnmappableCharacterException",
        "CharacterCodingException",
    )

private fun isJsRuntimeMalformedWire(e: Throwable): Boolean {
    val msg = e.message ?: return false
    // Kotlin/JS DataView / TextDecoder failures: "Offset is outside the bounds of the DataView"
    // (read past the frame) and "...not valid for encoding utf-8" (bad UTF-8).
    return "outside the bounds" in msg || "valid for encoding" in msg
}

/**
 * Runs [decode] and normalizes the *decode boundary* to a single typed failure: any
 * malformed-wire exception ([isMalformedWireException]) that is not already an [MqttException] is
 * rethrown as a [MalformedPacketException], so a caller feeding network bytes can distinguish
 * "the peer sent a malformed packet" (an [MqttException]) from "the transport died" (a socket
 * exception) — see MQTT §4.13, and DitchOoM/mqtt#13.
 *
 * Exceptions that are already [MqttException] (including `ProtocolError` and
 * `MalformedPacketException` itself) pass through unchanged, and genuine decoder bugs /
 * cancellation propagate unwrapped, so this never hides a bug behind a "malformed packet" label.
 */
inline fun <T> mappingMalformedWire(decode: () -> T): T =
    try {
        decode()
    } catch (e: MqttException) {
        throw e
    } catch (
        @Suppress("TooGenericExceptionCaught") e: Throwable,
    ) {
        if (isMalformedWireException(e)) {
            throw MalformedPacketException("Malformed MQTT packet from peer (${e::class.simpleName}: ${e.message})")
        }
        throw e
    }
