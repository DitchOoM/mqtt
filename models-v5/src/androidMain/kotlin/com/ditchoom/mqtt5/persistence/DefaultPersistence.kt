package com.ditchoom.mqtt5.persistence

import com.ditchoom.mqtt.InMemoryPersistence
import com.ditchoom.mqtt.Persistence
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

// See commonMain/DefaultPersistence.kt for the Phase B
// consumer-supplied-persistence rationale.
actual suspend fun newDefaultPersistence(
    androidContext: Any?,
    name: String,
    inMemory: Boolean,
): Persistence {
    if (!inMemory) {
        warnPersistentStorageUnavailable("v5 Android")
    }
    return InMemoryPersistence()
}

actual fun defaultDispatcher(
    nThreads: Int,
    name: String,
): CoroutineDispatcher = Dispatchers.IO
