package com.ditchoom.mqtt3.persistence

import app.cash.sqldelight.db.SqlDriver

expect fun sqlDriver(androidContext: Any?, name: String = "mqtt4.db", inMemory: Boolean = false): SqlDriver?
