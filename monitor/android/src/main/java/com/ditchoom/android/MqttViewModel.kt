package com.ditchoom.android

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.startup.AppInitializer
import com.ditchoom.mqtt.client.MqttService
import com.ditchoom.mqtt.client.MqttServiceInitializer
import kotlinx.coroutines.async

class MqttViewModel(application: Application) : AndroidViewModel(application) {
    private val _mqttService = viewModelScope.async { MqttService.getService(true, application) }

    suspend fun mqttService() = _mqttService.await()

}