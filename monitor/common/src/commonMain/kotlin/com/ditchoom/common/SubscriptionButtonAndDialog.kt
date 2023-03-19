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
fun SubscriptionButtonAndDialog(client: MqttClient) {
    val scope = rememberCoroutineScope()
    var openSubDialog by remember { mutableStateOf(false) }
    Button(onClick = {
        openSubDialog = true
    }) {
        Text("Subscribe")
    }
    if (openSubDialog) {
        var topicFilter by remember { mutableStateOf("") }
        var qosString by remember { mutableStateOf("") }
        var showMqtt5Extras by remember { mutableStateOf(false) }
        var noLocal by remember { mutableStateOf(false) }
        var retainAsPublished by remember { mutableStateOf(false) }
        var retainHandling by remember { mutableStateOf("0") }
        var serverReference by remember { mutableStateOf<String?>(null) }
        AlertDialog(onDismissRequest = {
            openSubDialog = false
        }, title = {
            Text(text = "Subscribe")
        },
            text = {
                Column {
                    inputTextField("Topic Filter", topicFilter) { topicFilter = it }
                    inputTextField("QoS", qosString) { qosString = it }
                    if (client.controlPacketFactory().protocolVersion == 5) {
                        checkBoxRow("Show MQTT 5 Extra Props", showMqtt5Extras) { showMqtt5Extras = it }
                        if (showMqtt5Extras) {
                            checkBoxRow("No Local", noLocal) { noLocal = it }
                            checkBoxRow("Retain As Published", retainAsPublished) { retainAsPublished = it }
                            inputTextField("Retain Handling", retainHandling) { retainHandling = it }
                            inputTextField("Server Reference", serverReference ?: "") {
                                serverReference = it
                            }
                        }
                    }
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
                        val qosInt = qosString.toIntOrNull()
                        if (qosInt == null || qosInt < 0 || qosInt > 2) {
                            println("invalid qos $qosString")
                            return@Button
                        }
                        val qos = when (qosInt) {
                            1 -> QualityOfService.AT_LEAST_ONCE
                            2 -> QualityOfService.EXACTLY_ONCE
                            else -> QualityOfService.AT_MOST_ONCE
                        }

                        val retainHandlingVal = when (retainHandling) {
                            "1" -> ISubscription.RetainHandling.SEND_RETAINED_MESSAGES_AT_SUBSCRIBE_ONLY_IF_SUBSCRIBE_DOESNT_EXISTS
                            "2" -> ISubscription.RetainHandling.DO_NOT_SEND_RETAINED_MESSAGES
                            else -> ISubscription.RetainHandling.SEND_RETAINED_MESSAGES_AT_TIME_OF_SUBSCRIBE
                        }
                        val sub = client.controlPacketFactory().subscribe(
                            topic,
                            qos,
                            noLocal,
                            retainAsPublished,
                            retainHandlingVal,
                            serverReference
                        )
                        scope.launch { client.subscribe(sub) }
                        openSubDialog = false
                    }) {
                    Text("Queue Subscribe Request")
                }
            },
            dismissButton = {
                Button(
                    onClick = {
                        openSubDialog = false
                    }) {
                    Text("Cancel Subscribe Request")
                }
            })
    }
}