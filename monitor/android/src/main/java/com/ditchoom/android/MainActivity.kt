package com.ditchoom.android

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material.MaterialTheme
import androidx.lifecycle.lifecycleScope
import com.ditchoom.common.App
import com.ditchoom.mqtt.client.LocalMqttService

class MainActivity : AppCompatActivity() {
    val model: MqttViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        LocalMqttService.buildService(applicationContext) { service ->
            setContent {
                MaterialTheme {
                    App(service)
                }
            }
        }

    }
}