package com.ditchoom.mqtt5.persistence

import com.ditchoom.mqtt.Persistence
import kotlinx.coroutines.CoroutineDispatcher

expect suspend fun newDefaultPersistence(
    androidContext: Any? = null,
    name: String = "mqtt5.db",
    inMemory: Boolean = false,
): Persistence

expect fun defaultDispatcher(
    nThreads: Int,
    name: String,
): CoroutineDispatcher
