package com.ditchoom.mqtt3.persistence

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.ditchoom.Mqtt4

actual fun sqlDriver(androidContext: Any?, name: String, inMemory: Boolean): SqlDriver? {
    val driver = JdbcSqliteDriver(if (inMemory) JdbcSqliteDriver.IN_MEMORY else "jdbc:sqlite:file:$name")
    Mqtt4.Schema.create(driver)
    return driver
}
