package com.ditchoom.android

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.ditchoom.common.App
import com.ditchoom.common.LoadingScreen
import com.ditchoom.mqtt.client.MqttService

class MainActivity : AppCompatActivity() {
    private val model: MqttViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val (serviceOrNull, setService) = remember { mutableStateOf<MqttService?>(null) }
                if (serviceOrNull != null) {
                    App(serviceOrNull)
                } else {
                    LaunchedEffect(this) {
                        setService(model.mqttService())
                    }
                    LoadingScreen()
                }
            }
        }
    }
}