package com.ditchoom.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import com.ditchoom.mqtt.client.MqttClient
import kotlinx.coroutines.launch

@Composable
fun MqttConnectionViewer(
    client: MqttClient,
    mqttLogs: String,
    onDisconnectButtonSelected: () -> Unit
) {
    val scope = rememberCoroutineScope()
    LazyColumn {
        item {
            Row {
                Button(onClick = {
                    scope.launch {
                        client.shutdown(true)
                    }
                    onDisconnectButtonSelected()
                }) {
                    Text("Disconnect")
                }
                SubscriptionButtonAndDialog(client)
                PublishButtonAndDialog(client)
                UnsubscriptionButtonAndDialog(client)
            }
        }
        item {
            Text("\r\nReady ${client.broker.connectionOps.first()} ${client.broker.connectionRequest}\r\n$mqttLogs")
        }
    }
}
