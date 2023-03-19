package com.ditchoom.mqtt3.persistence

import app.cash.sqldelight.db.SqlDriver

actual fun sqlDriver(androidContext: Any?, name: String, inMemory: Boolean): SqlDriver? = null
