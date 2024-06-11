package com.ditchoom.mqtt5.persistence

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.ditchoom.Mqtt5

actual fun sqlDriver(
    androidContext: Any?,
    name: String,
    inMemory: Boolean,
): SqlDriver? =
    NativeSqliteDriver(
        Mqtt5.Schema,
        name,
        onConfiguration = {
            it.copy(inMemory = inMemory)
        },
    )
