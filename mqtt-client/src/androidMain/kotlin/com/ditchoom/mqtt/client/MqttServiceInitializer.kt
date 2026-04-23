package com.ditchoom.mqtt.client

import android.content.Context
import androidx.startup.Initializer
import com.ditchoom.mqtt.client.net.defaultSingleConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class MqttServiceInitializer : Initializer<LocalMqttService> {
    override fun create(context: Context): LocalMqttService =
        runBlocking(Dispatchers.Default) {
            LocalMqttService.buildService(
                connectionFactory = ::defaultSingleConnection,
                androidContext = context,
            )
        }

    override fun dependencies(): MutableList<Class<out Initializer<*>>> = mutableListOf()
}
