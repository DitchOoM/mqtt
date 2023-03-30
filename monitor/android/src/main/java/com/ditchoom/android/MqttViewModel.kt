package com.ditchoom.android

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.startup.AppInitializer
import com.ditchoom.mqtt.client.MqttServiceInitializer

class MqttViewModel(application: Application) : AndroidViewModel(application) {
    private val service = AppInitializer.getInstance(getApplication())
        .initializeComponent(MqttServiceInitializer::class.java)

    public val mqttRepository = MqttRepository(service)


}