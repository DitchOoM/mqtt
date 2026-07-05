package com.ditchoom.mqtt.client.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration

actual typealias TestRunResult = Unit

actual fun runTestNoTimeSkipping(
    timeout: Duration,
    block: suspend CoroutineScope.() -> Unit,
): TestRunResult =
    runBlocking {
        withTimeout(timeout) {
            withContext(Dispatchers.Default) {
                block()
            }
        }
    }
