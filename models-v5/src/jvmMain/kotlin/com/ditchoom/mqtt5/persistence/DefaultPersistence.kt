package com.ditchoom.mqtt5.persistence

import com.ditchoom.mqtt.Persistence
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.newSingleThreadContext

actual suspend fun newDefaultPersistence(
    androidContext: Any?,
    name: String,
    inMemory: Boolean,
): Persistence = SqlDatabasePersistence(sqlDriver(androidContext, name, inMemory)!!)

actual fun defaultDispatcher(
    nThreads: Int,
    name: String,
): CoroutineDispatcher = newSingleThreadContext("Mqtt5-SQL")
