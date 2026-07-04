package com.ditchoom.mqtt3.controlpacket.fuzz

import com.code_intelligence.jazzer.junit.FuzzTest
import com.ditchoom.mqtt3.controlpacket.decodeProductionV4

/**
 * Coverage-guided (libFuzzer via jazzer-junit) fuzzing of the v4 decode path.
 *
 * Modes:
 * - default `jvmTest`: skipped entirely (gated behind -PfuzzTests, see build.gradle.kts).
 * - `jvmTest -PfuzzTests`: regression mode — replays the seed corpus and any committed
 *   `crash-*` inputs from src/jvmTest/resources/.../ControlPacketV4JazzerFuzzTestInputs/.
 * - `JAZZER_FUZZ=1 ./gradlew :models-v4:jvmTest -PfuzzTests --tests '*JazzerFuzzTest'`:
 *   real fuzzing until [FuzzTest.maxDuration] or a crash; new crashers are written into
 *   the inputs resource directory to be committed as regression cases.
 */
class ControlPacketV4JazzerFuzzTest {
    @FuzzTest(maxDuration = "10m")
    fun decodeArbitraryBytes(data: ByteArray) {
        if (data.size > 1 shl 16) return
        try {
            decodeProductionV4(bytesToReadBuffer(data))
        } catch (e: Throwable) {
            // Rethrow only unexpected failure modes so Jazzer records them as findings.
            if (!isAcceptedDecodeFailure(e)) throw e
        }
    }
}
