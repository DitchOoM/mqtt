@file:Suppress("ktlint:standard:filename")

package com.ditchoom.mqtt.client.net

import kotlinx.coroutines.CoroutineScope
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Platform-specific return type for test functions.
 * On JVM/Native: Unit (required by test framework).
 * On JS: Any (allows returning Promise for mocha async test tracking).
 */
expect class TestRunResult

/**
 * Runs a test with real-time timeout (no virtual time skipping).
 * Platform-specific: uses runBlocking on JVM/Native, GlobalScope.promise on JS.
 *
 * This is needed because kotlinx.coroutines.test.runTest uses virtual time,
 * which doesn't work for real network I/O on JS (mocha needs a Promise).
 */
expect fun runTestNoTimeSkipping(
    timeout: Duration = 30.seconds,
    block: suspend CoroutineScope.() -> Unit,
): TestRunResult
