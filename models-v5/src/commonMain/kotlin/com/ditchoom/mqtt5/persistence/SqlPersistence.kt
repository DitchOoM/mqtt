package com.ditchoom.mqtt5.persistence

import app.cash.sqldelight.db.SqlDriver

expect fun sqlDriver(androidContext: Any?, name: String = "mqtt5.db", inMemory: Boolean = false): SqlDriver?
