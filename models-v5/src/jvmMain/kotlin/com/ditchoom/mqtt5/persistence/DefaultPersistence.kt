package com.ditchoom.mqtt5.persistence

import com.ditchoom.mqtt.Persistence
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext

actual suspend fun newDefaultPersistence(
    androidContext: Any?,
    name: String,
    inMemory: Boolean,
): Persistence = SqlDatabasePersistence(sqlDriver(androidContext, name, inMemory)!!)

@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
actual fun defaultDispatcher(
    nThreads: Int,
    name: String,
): CoroutineDispatcher = newSingleThreadContext("Mqtt5-SQL")
