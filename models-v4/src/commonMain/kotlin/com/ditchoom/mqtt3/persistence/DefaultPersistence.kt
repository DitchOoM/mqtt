@file:kotlin.jvm.JvmName("DefaultPersistenceCommon")

package com.ditchoom.mqtt3.persistence

import com.ditchoom.mqtt.Persistence
import kotlinx.coroutines.CoroutineDispatcher

expect suspend fun newDefaultPersistence(
    androidContext: Any? = null,
    name: String = "mqtt4.db",
    inMemory: Boolean = false,
): Persistence

expect fun defaultDispatcher(
    nThreads: Int,
    name: String,
): CoroutineDispatcher

// Common-side helper for the per-platform DefaultPersistence actuals to surface
// "consumer asked for durable storage, none is currently bundled" without
// pulling println-vs-console-vs-NSLog platform decisions into each actual.
// Every supported platform routes println to its native log sink
// (stdout / Logcat / NSLog).
//
// v4 SQL / IDB persistence implementations were removed alongside v5's (see
// commit 8d426523) pending the Phase B consumer-supplied Persistence design —
// the Persistence interface lives in models-base and consumers can implement
// their own storage backend.
internal fun warnPersistentStorageUnavailable(platform: String) {
    println(
        "WARNING: $platform persistent storage was requested but is not bundled " +
            "in this build. Falling back to InMemoryPersistence — durable Persistence " +
            "must be supplied by the consumer via the com.ditchoom.mqtt.Persistence " +
            "interface (see models-base).",
    )
}
