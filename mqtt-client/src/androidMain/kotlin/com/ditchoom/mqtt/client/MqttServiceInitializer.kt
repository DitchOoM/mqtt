package com.ditchoom.mqtt.client

import android.content.Context
import androidx.startup.Initializer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class MqttServiceInitializer : Initializer<LocalMqttService> {
    override fun create(context: Context): LocalMqttService {
        return runBlocking(Dispatchers.Default) { LocalMqttService.buildService(context) }
    }

    override fun dependencies(): MutableList<Class<out Initializer<*>>> = mutableListOf()
}
