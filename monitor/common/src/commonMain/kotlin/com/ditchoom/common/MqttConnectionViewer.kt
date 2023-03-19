package com.ditchoom.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import com.ditchoom.mqtt.client.MqttService
import com.ditchoom.mqtt.connection.MqttBroker
import kotlinx.coroutines.launch

@Composable
fun MqttConnectionViewer(broker: Pair<MqttBroker, MqttService>, mqttLogs: String, onDisconnectButtonSelected: () -> Unit) {
    val (mqttBroker, service) = broker
    val client = service.getClient(mqttBroker) ?: return
    val scope = rememberCoroutineScope()
    LazyColumn {
        item {
            Row {
                Button(onClick = {
                    scope.launch {
                        service.stop(mqttBroker)
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
            Text("\r\nReady ${broker.first.connectionOps.first()} ${broker.first.connectionRequest}\r\n$mqttLogs")
        }
    }
}
