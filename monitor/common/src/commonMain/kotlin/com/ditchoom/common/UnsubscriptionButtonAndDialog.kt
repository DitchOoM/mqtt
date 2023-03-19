@file:OptIn(ExperimentalMaterialApi::class)

package com.ditchoom.common

import androidx.compose.foundation.layout.Column
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.ditchoom.mqtt.client.MqttClient
import com.ditchoom.mqtt.controlpacket.ISubscription
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.Topic
import kotlinx.coroutines.launch

@Composable
fun UnsubscriptionButtonAndDialog(client: MqttClient) {
    val scope = rememberCoroutineScope()
    var openSubDialog by remember { mutableStateOf(false) }
    Button(onClick = {
        openSubDialog = true
    }) {
        Text("Unsubscribe")
    }
    if (openSubDialog) {
        var topicFilter by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = {
            openSubDialog = false
        }, title = {
            Text(text = "Subscribe")
        },
            text = {
                Column {
                    inputTextField("Topic Filter", topicFilter) {topicFilter = it}
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val topic = Topic.fromOrNull(topicFilter, Topic.Type.Filter)
                        if (topic == null) {
                            println("invalid topic $topicFilter")
                            return@Button
                        }
                        val unsub = client.controlPacketFactory().unsubscribe(
                            topic
                        )
                        scope.launch { client.unsubscribe(unsub) }
                        openSubDialog = false
                    }) {
                    Text("Queue Unsubscribe Request")
                }
            },
            dismissButton = {
                Button(
                    onClick = {
                        openSubDialog = false
                    }) {
                    Text("Cancel Unsubscribe Request")
                }
            })
    }
}