package com.ditchoom.mqtt.client.ipc

import android.app.Service
import android.content.Intent
import androidx.startup.AppInitializer
import com.ditchoom.mqtt.client.LocalMqttService
import com.ditchoom.mqtt.client.MqttServiceInitializer
import kotlinx.coroutines.runBlocking

class MqttManagerService : Service() {
    private lateinit var mqttService: LocalMqttService

    override fun onCreate() {
        super.onCreate()
        mqttService = AppInitializer.getInstance(this).initializeComponent(MqttServiceInitializer::class.java)
    }

    override fun onBind(intent: Intent) = AndroidRemoteMqttServiceWorker(mqttService)

    override fun onDestroy() {
        runBlocking {
            mqttService.shutdownAndCleanup()
        }
        super.onDestroy()
    }
}
