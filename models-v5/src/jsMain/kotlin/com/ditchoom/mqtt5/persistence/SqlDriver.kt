package com.ditchoom.mqtt5.persistence

import app.cash.sqldelight.db.SqlDriver

actual fun sqlDriver(androidContext: Any?, name: String, inMemory: Boolean): SqlDriver? = null
