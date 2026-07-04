package com.ditchoom.mqtt.controlpacket.fuzz

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.WireSize
import com.ditchoom.mqtt.controlpacket.MqttRemainingLengthCodec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Deterministic fuzzing of the MQTT Remaining Length variable-byte-integer codec
 * (v3.1.1 §2.2.3 / v5 §2.1.4). Opt-in via -PfuzzTests; see build.gradle.kts.
 */
class RemainingLengthFuzzTest {
    private val maxRemainingLength = 0x0FFF_FFFFu

    @Test
    fun randomBytesDecodeSafely() {
        repeat(20_000) { i ->
            val seed = FUZZ_BASE_SEED + i
            val rng = Random(seed)
            val bytes = ByteArray(rng.nextInt(0, 7)) { rng.nextInt(256).toByte() }
            fuzzCase(i, seed, bytes) {
                val buffer = bytesToReadBuffer(bytes)
                val value = MqttRemainingLengthCodec.decode(buffer, DecodeContext.Empty)
                assertTrue(value <= maxRemainingLength, "decoded VBI above spec maximum: $value")
                assertTrue(buffer.position() <= 4, "VBI decode consumed more than 4 bytes")
            }
        }
    }

    @Test
    fun mutatedValidVbiDecodesSafely() {
        repeat(10_000) { i ->
            val seed = FUZZ_BASE_SEED + 1_000_000 + i
            val rng = Random(seed)
            val value = rng.nextInt(0, maxRemainingLength.toInt() + 1).toUInt()
            val mutated = mutate(encodeVbi(value), rng)
            fuzzCase(i, seed, mutated) {
                MqttRemainingLengthCodec.decode(bytesToReadBuffer(mutated), DecodeContext.Empty)
            }
        }
    }

    @Test
    fun roundTripRandomValues() {
        repeat(5_000) { i ->
            val seed = FUZZ_BASE_SEED + 2_000_000 + i
            val rng = Random(seed)
            val value = rng.nextInt(0, maxRemainingLength.toInt() + 1).toUInt()
            val encoded = encodeVbi(value)
            fuzzCase(i, seed, encoded) {
                assertEquals(
                    WireSize.Exact(encoded.size),
                    MqttRemainingLengthCodec.wireSize(value, EncodeContext.Empty),
                    "wireSize disagrees with encoded byte count for $value",
                )
                val decoded = MqttRemainingLengthCodec.decode(bytesToReadBuffer(encoded), DecodeContext.Empty)
                assertEquals(value, decoded, "VBI round-trip mismatch")
            }
        }
    }

    private fun encodeVbi(value: UInt): ByteArray {
        val buffer = BufferFactory.Default.allocate(4)
        MqttRemainingLengthCodec.encode(buffer, value, EncodeContext.Empty)
        buffer.resetForRead()
        return buffer.readByteArray(buffer.remaining())
    }
}
