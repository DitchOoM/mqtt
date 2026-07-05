package com.ditchoom.mqtt3.controlpacket.fuzz

import com.ditchoom.mqtt3.controlpacket.decodeProductionV4
import com.ditchoom.mqtt3.controlpacket.decodeV4
import com.ditchoom.mqtt3.controlpacket.encodeToReadBuffer
import kotlin.random.Random
import kotlin.test.Test

/**
 * Deterministic fuzzing of the v4 control-packet decode path. Targets
 * [decodeProductionV4] — the same `decodeAggregating` boundary production runs via
 * `MqttCodec.decode` — so the hardening points at the real wire-decode surface.
 * Malformed input must surface as an accepted decode failure (see
 * [isAcceptedDecodeFailure]) or decode successfully — anything else is a decoder bug.
 * Opt-in via -PfuzzTests; see build.gradle.kts.
 */
class ControlPacketV4FuzzTest {
    @Test
    fun randomBytesDecodeSafely() {
        repeat(10_000) { i ->
            val seed = FUZZ_BASE_SEED + i
            val rng = Random(seed)
            val bytes = ByteArray(rng.nextInt(0, 64)) { rng.nextInt(256).toByte() }
            // Bias half the corpus toward plausible first bytes (valid packet type in the
            // high nibble) so the per-type codecs are reached, not just the dispatcher.
            if (bytes.isNotEmpty() && rng.nextBoolean()) {
                bytes[0] = ((1 + rng.nextInt(14)) shl 4 or rng.nextInt(16)).toByte()
            }
            fuzzCase(i, seed, bytes) {
                decodeProductionV4(bytesToReadBuffer(bytes))
            }
        }
    }

    @Test
    fun mutatedValidPacketsDecodeSafely() {
        repeat(2_000) { i ->
            val seed = FUZZ_BASE_SEED + 1_000_000 + i
            val rng = Random(seed)
            val encoded = encodeToReadBuffer(randomValidV4Packet(rng))
            val mutated = mutate(encoded.readByteArray(encoded.remaining()), rng)
            fuzzCase(i, seed, mutated) {
                decodeProductionV4(bytesToReadBuffer(mutated))
            }
        }
    }

    @Test
    fun roundTripRandomValidPackets() {
        repeat(2_000) { i ->
            val seed = FUZZ_BASE_SEED + 2_000_000 + i
            val rng = Random(seed)
            val packet = randomValidV4Packet(rng)
            val encoded = encodeToReadBuffer(packet)
            val bytes = encoded.readByteArray(encoded.remaining())
            fuzzCase(i, seed, bytes) {
                assertV4PacketsEqual(packet, decodeV4(bytesToReadBuffer(bytes)))
            }
        }
    }
}
