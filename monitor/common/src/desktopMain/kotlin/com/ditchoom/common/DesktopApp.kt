package com.ditchoom.common

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ditchoom.mqtt.client.LocalMqttService

@Preview
@Composable
fun AppPreview() {
    var service by remember { mutableStateOf<LocalMqttService?>(null) }
    LaunchedEffect("MQTTService") {
        service = LocalMqttService.buildService()
    }
    val serviceLocal = service
    if (serviceLocal != null) {
        App(serviceLocal)
    }
}