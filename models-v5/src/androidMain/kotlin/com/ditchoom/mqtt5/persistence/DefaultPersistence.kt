package com.ditchoom.mqtt5.persistence

import com.ditchoom.mqtt.InMemoryPersistence
import com.ditchoom.mqtt.Persistence
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual suspend fun newDefaultPersistence(
    androidContext: Any?,
    name: String,
    inMemory: Boolean,
): Persistence =
    try {
        SqlDatabasePersistence(sqlDriver(androidContext, name, inMemory)!!)
    } catch (t: Throwable) {
        InMemoryPersistence()
    }

actual fun defaultDispatcher(
    nThreads: Int,
    name: String,
): CoroutineDispatcher = Dispatchers.IO
