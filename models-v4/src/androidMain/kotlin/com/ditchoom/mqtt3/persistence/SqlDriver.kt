package com.ditchoom.mqtt3.persistence

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.ditchoom.Mqtt4

actual fun sqlDriver(
    androidContext: Any?,
    name: String,
    inMemory: Boolean,
): SqlDriver? =
    if (androidContext != null) {
        AndroidSqliteDriver(
            Mqtt4.Schema,
            androidContext as Context,
            if (inMemory) {
                null
            } else {
                name
            },
        )
    } else {
        val driver =
            JdbcSqliteDriver(
                if (inMemory) {
                    JdbcSqliteDriver.IN_MEMORY
                } else {
                    name
                },
            )
        Mqtt4.Schema.create(driver)
        driver
    }
