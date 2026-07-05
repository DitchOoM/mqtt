package com.ditchoom.mqtt.controlpacket.fuzz

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.DecodeException
import com.ditchoom.mqtt.MqttException
import kotlin.random.Random
import kotlin.test.fail

/*
 * Deterministic fuzzing support. This file is maintained in triplicate — models-base
 * (com.ditchoom.mqtt.controlpacket.fuzz), models-v4 (com.ditchoom.mqtt3.controlpacket.fuzz),
 * models-v5 (com.ditchoom.mqtt5.controlpacket.fuzz) — identical except for the package line:
 * a shared test-utils module is disproportionately heavy given the per-module
 * publish/dokka/codecSchema convention stack, so keep the three files in sync by hand.
 */

/** Base seed; each iteration derives its own `Random(FUZZ_BASE_SEED + iteration)`. */
const val FUZZ_BASE_SEED = 0x6D71_7474_6675_7A7AL // "mqttfuzz"

fun bytesToReadBuffer(bytes: ByteArray): ReadBuffer {
    val buffer = BufferFactory.Default.allocate(bytes.size)
    buffer.writeBytes(bytes)
    buffer.resetForRead()
    return buffer
}

fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

/**
 * Exceptions a decoder is allowed to throw on malformed/truncated input. Anything else
 * (NPE, ClassCastException, IllegalStateException, ...) is a decoder bug.
 *
 * This predicate is a **superset** covering two different fuzz targets:
 *   1. the models-base VBI fuzzer, which decodes `MqttRemainingLengthCodec` directly and throws the
 *      raw families (truncation → buffer underflow) unwrapped; and
 *   2. the v4/v5 production-mirror path (`decodeProductionV{4,5}`, which — like `MqttCodec.decode` —
 *      now remaps malformed wire bytes to [MqttException] via `mappingMalformedWire`, DitchOoM/mqtt#13).
 * Accepting both the wrapped [MqttException] and the raw families keeps the file identical across all
 * three modules while matching whichever layer each fuzzer exercises:
 *
 * - [MqttException] covers MalformedPacketException / ProtocolError / MalformedInvalidVariableByteInteger,
 *   plus everything the production mirror now wraps.
 * - [DecodeException]: the generated codecs' native "malformed wire" signal (unknown packet type,
 *   body-overrun, bad length prefix) — surfaces raw from the direct-codec VBI fuzzer.
 * - IllegalArgumentException: variant `init` validation (e.g. invalid reason codes) surfaces as IAE.
 * - The name-matched set mirrors the allowlist in the KSP-generated `peekFrameSize`: truncated
 *   input hits the platform buffer's underflow class, which has no common supertype across targets.
 * - Malformed UTF-8 in a length-prefixed string (e.g. a PUBLISH topic) surfaces as the JVM
 *   charset decoder's CharacterCodingException subclasses (MalformedInputException /
 *   UnmappableCharacterException) on JVM, and as `CharacterCodingException` itself on Kotlin/Native.
 */
fun isAcceptedDecodeFailure(e: Throwable): Boolean =
    e is MqttException ||
        e is DecodeException ||
        e is IllegalArgumentException ||
        e::class.simpleName in acceptedBufferExceptionNames ||
        isAcceptedJsRuntimeDecodeFailure(e)

/**
 * On Kotlin/JS the buffer layer's underlying `DataView` / `TextDecoder` operations reject
 * truncated or malformed input by throwing raw JS `RangeError` / `TypeError`s, whose
 * `::class.simpleName` is null — so they can't be name-matched like their JVM/Native
 * equivalents in [acceptedBufferExceptionNames]. They ARE accepted decode failures (the
 * same truncation / bad-UTF-8 families), recognized here by their stable runtime messages:
 *  - "Offset is outside the bounds of the DataView" — a read past the frame (underflow analog)
 *  - "...not valid for encoding utf-8" — malformed UTF-8 in a length-prefixed string
 * Genuine decoder bugs on JS still surface as named Kotlin exceptions (NullPointerException,
 * IllegalStateException, ClassCastException), which are NOT matched here.
 */
private fun isAcceptedJsRuntimeDecodeFailure(e: Throwable): Boolean {
    val msg = e.message ?: return false
    return "outside the bounds" in msg || "valid for encoding" in msg
}

private val acceptedBufferExceptionNames =
    setOf(
        "BufferUnderflowException",
        "IndexOutOfBoundsException",
        "ArrayIndexOutOfBoundsException",
        "MalformedInputException",
        "UnmappableCharacterException",
        "CharacterCodingException",
    )

/**
 * Runs one fuzz iteration. On a non-accepted throwable (including assertion failures inside
 * [block]) fails with the seed and a hex dump of [input] — the hex dump is the canonical
 * repro, since kotlin.random's algorithm is not guaranteed stable across Kotlin versions.
 */
inline fun fuzzCase(
    iteration: Int,
    seed: Long,
    input: ByteArray,
    block: () -> Unit,
) {
    try {
        block()
    } catch (e: Throwable) {
        if (!isAcceptedDecodeFailure(e)) {
            fail(
                "FUZZ FAILURE iteration=$iteration seed=$seed len=${input.size} " +
                    "hex=${input.toHex()} -> ${e::class.simpleName}: ${e.message}",
            )
        }
    }
}

// --- Mutation operators (each returns a fresh array; input is never modified) ---

fun flipRandomBit(
    bytes: ByteArray,
    rng: Random,
): ByteArray {
    if (bytes.isEmpty()) return bytes
    val out = bytes.copyOf()
    val index = rng.nextInt(out.size)
    out[index] = (out[index].toInt() xor (1 shl rng.nextInt(8))).toByte()
    return out
}

fun truncate(
    bytes: ByteArray,
    rng: Random,
): ByteArray = if (bytes.isEmpty()) bytes else bytes.copyOf(rng.nextInt(bytes.size))

fun insertRandomByte(
    bytes: ByteArray,
    rng: Random,
): ByteArray {
    val index = rng.nextInt(bytes.size + 1)
    val out = ByteArray(bytes.size + 1)
    bytes.copyInto(out, destinationOffset = 0, startIndex = 0, endIndex = index)
    out[index] = rng.nextInt(256).toByte()
    bytes.copyInto(out, destinationOffset = index + 1, startIndex = index, endIndex = bytes.size)
    return out
}

fun deleteRandomByte(
    bytes: ByteArray,
    rng: Random,
): ByteArray {
    if (bytes.isEmpty()) return bytes
    val index = rng.nextInt(bytes.size)
    val out = ByteArray(bytes.size - 1)
    bytes.copyInto(out, destinationOffset = 0, startIndex = 0, endIndex = index)
    bytes.copyInto(out, destinationOffset = index, startIndex = index + 1, endIndex = bytes.size)
    return out
}

/**
 * Corrupts the remaining-length VBI region (bytes 1..4 of a control packet), biased toward
 * setting continuation bits so length-vs-content mismatches are exercised.
 */
fun corruptLengthField(
    bytes: ByteArray,
    rng: Random,
): ByteArray {
    if (bytes.size < 2) return bytes
    val out = bytes.copyOf()
    val index = 1 + rng.nextInt(minOf(4, out.size - 1))
    val raw = rng.nextInt(256)
    out[index] = (if (rng.nextBoolean()) raw or 0x80 else raw).toByte()
    return out
}

private val mutationOps =
    listOf(::flipRandomBit, ::truncate, ::insertRandomByte, ::deleteRandomByte, ::corruptLengthField)

/** Applies 1–4 randomly chosen mutation operators in sequence. */
fun mutate(
    bytes: ByteArray,
    rng: Random,
): ByteArray {
    var out = bytes
    repeat(1 + rng.nextInt(4)) {
        out = mutationOps[rng.nextInt(mutationOps.size)](out, rng)
    }
    return out
}
