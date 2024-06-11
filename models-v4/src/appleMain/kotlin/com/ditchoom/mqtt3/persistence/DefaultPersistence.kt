package com.ditchoom.mqtt3.persistence

import com.ditchoom.mqtt.Persistence
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.newFixedThreadPoolContext

actual suspend fun newDefaultPersistence(
    androidContext: Any?,
    name: String,
    inMemory: Boolean,
): Persistence = SqlDatabasePersistence(sqlDriver(androidContext, name, inMemory)!!)

actual fun defaultDispatcher(
    nThreads: Int,
    name: String,
): CoroutineDispatcher = newFixedThreadPoolContext(123, "mqtt")
