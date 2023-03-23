package com.ditchoom.android

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.ditchoom.common.App
import com.ditchoom.mqtt.client.MqttService

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MqttService.buildService(applicationContext) { service ->
            setContent {
                MaterialTheme {
                    App(service)
                }
            }
        }

    }
}