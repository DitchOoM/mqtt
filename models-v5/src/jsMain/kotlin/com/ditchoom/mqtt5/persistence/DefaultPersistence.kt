package com.ditchoom.mqtt5.persistence

import com.ditchoom.mqtt.InMemoryPersistence
import com.ditchoom.mqtt.Persistence
import js.errors.ReferenceError
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import web.idb.IDBFactory

actual suspend fun newDefaultPersistence(androidContext: Any?, name: String, inMemory: Boolean): Persistence {
    val indexedDb =
        try {
            js("window.indexedDB || window.mozIndexedDB || window.webkitIndexedDB || window.msIndexedDB || window.shimIndexedDB") as IDBFactory
        } catch (e: ReferenceError) {
            return InMemoryPersistence()
        }
    return IDBPersistence.idbPersistence(indexedDb, name)
}

actual fun defaultDispatcher(nThreads: Int, name: String): CoroutineDispatcher = Dispatchers.Default
