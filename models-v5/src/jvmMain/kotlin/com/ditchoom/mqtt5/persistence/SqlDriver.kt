package com.ditchoom.mqtt5.persistence

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.ditchoom.Mqtt5

actual fun sqlDriver(androidContext: Any?, name: String, inMemory: Boolean): SqlDriver? {
    val driver: SqlDriver = JdbcSqliteDriver(if (inMemory) JdbcSqliteDriver.IN_MEMORY else "jdbc:sqlite:file:$name")
    Mqtt5.Schema.create(driver)
    return driver
}
