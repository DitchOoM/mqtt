package com.ditchoom.mqtt3.persistence

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.ditchoom.Mqtt4

actual fun sqlDriver(androidContext: Any?, name: String, inMemory: Boolean): SqlDriver? =
    NativeSqliteDriver(
        Mqtt4.Schema,
        name,
        onConfiguration = {
            it.copy(inMemory = inMemory)
        }
    )
