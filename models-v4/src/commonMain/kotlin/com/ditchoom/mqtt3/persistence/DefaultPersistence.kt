package com.ditchoom.mqtt3.persistence

import com.ditchoom.mqtt.Persistence
import kotlinx.coroutines.CoroutineDispatcher

expect suspend fun newDefaultPersistence(
    androidContext: Any? = null,
    name: String = "mqtt4.db",
    inMemory: Boolean = false
): Persistence

expect fun defaultDispatcher(nThreads: Int, name: String): CoroutineDispatcher
