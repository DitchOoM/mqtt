package com.ditchoom.mqtt5.persistence

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.ditchoom.Mqtt5

actual fun sqlDriver(androidContext: Any?, name: String, inMemory: Boolean): SqlDriver? = if (androidContext != null) {
    AndroidSqliteDriver(
        Mqtt5.Schema,
        androidContext as Context,
        if (inMemory) {
            null
        } else {
            name
        }
    )
} else {
    val driver = JdbcSqliteDriver(
        if (inMemory) {
            JdbcSqliteDriver.IN_MEMORY
        } else {
            name
        }
    )
    Mqtt5.Schema.create(driver)
    driver
}
