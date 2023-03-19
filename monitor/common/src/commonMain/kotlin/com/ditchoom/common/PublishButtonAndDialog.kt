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
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.toReadBuffer
import com.ditchoom.mqtt.client.MqttClient
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.Topic
import kotlinx.coroutines.launch

@Composable
fun PublishButtonAndDialog(client: MqttClient) {
    val scope = rememberCoroutineScope()
    var openPublishDialog by remember { mutableStateOf(false) }
    Button(onClick = {
        openPublishDialog = true
    }) {
        Text("Publish")
    }
    if (openPublishDialog) {
        var topicName by remember { mutableStateOf("") }
        var qosString by remember { mutableStateOf("") }
        var hasPayload by remember { mutableStateOf(false) }
        var payload by remember { mutableStateOf("") }
        var showMqtt5Extras by remember { mutableStateOf(false) }
        var hasMessageExpiryInterval by remember { mutableStateOf(false) }
        var messageExpiryInterval by remember { mutableStateOf("0") }
        var hasTopicAlias by remember { mutableStateOf(false) }
        var topicAlias by remember { mutableStateOf("") }
        var hasResponseTopic by remember { mutableStateOf(false) }
        var responseTopic by remember { mutableStateOf("") }
        var hasCorrelationData by remember { mutableStateOf(false) }
        var correlationData by remember { mutableStateOf("") }
        var hasContentType by remember { mutableStateOf(false) }
        var contentType by remember { mutableStateOf("") }

        AlertDialog(onDismissRequest = {
            openPublishDialog = false
        }, title = {
            Text(text = "Subscribe")
        },
            text = {
                Column {
                    inputTextField("Topic Name", topicName) {topicName = it}
                    inputTextField("QoS", qosString){qosString = it}
                    checkBoxRow("Has Payload", hasPayload) { hasPayload = it }
                    if (hasPayload) {
                        inputTextField("Payload", payload) { payload = it }
                    }
                    if (client.controlPacketFactory().protocolVersion == 5) {
                        checkBoxRow("Show MQTT 5 Extra Props", showMqtt5Extras) { showMqtt5Extras = it }
                        if (showMqtt5Extras) {
                            checkBoxRow("Has Message Expiry Interval", hasMessageExpiryInterval) { hasMessageExpiryInterval = it }
                            if (hasMessageExpiryInterval) {
                                inputTextField("Message Expiry Interval Seconds", messageExpiryInterval) { messageExpiryInterval = it }
                            }
                            checkBoxRow("Has Topic Alias", hasTopicAlias) { hasTopicAlias = it }
                            if (hasTopicAlias) {
                                inputTextField("Topic Alias", topicAlias) { topicAlias = it }
                            }
                            checkBoxRow("Has Response Topic", hasResponseTopic) { hasResponseTopic = it }
                            if (hasResponseTopic) {
                                inputTextField("Response Topic", responseTopic) { responseTopic = it }
                            }
                            checkBoxRow("Has Correlation Data", hasCorrelationData) { hasCorrelationData = it }
                            if (hasCorrelationData) {
                                inputTextField("Correlation Data", correlationData) { correlationData = it }
                            }
                            checkBoxRow("Has Content Type", hasContentType) { hasContentType = it }
                            if (hasContentType) {
                                inputTextField("Content Type", contentType) { contentType = it }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val topic = Topic.fromOrNull(topicName, Topic.Type.Name)
                        if (topic == null) {
                            println("invalid topic $topicName")
                            return@Button
                        }
                        val payloadValue = if (hasPayload) payload.toReadBuffer(Charset.UTF8) else null
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
                        val messageExpiryIntervalSeconds = if (hasMessageExpiryInterval) {
                            messageExpiryInterval.toLongOrNull()
                        } else {
                            null
                        }
                        val topicAliasValue = if (hasTopicAlias) {
                            topicAlias.toIntOrNull()
                        } else {
                            null
                        }
                        val responseTopicValue = if (hasResponseTopic) {
                            Topic.fromOrNull(responseTopic, Topic.Type.Name)
                        } else {
                            null
                        }

                        val correlationDataBuffer = if (hasCorrelationData) {
                            correlationData.toReadBuffer(Charset.UTF8)
                        } else {
                            null
                        }
                        val contentTypeValue = if (hasContentType) contentType else null
                        val pub = client.controlPacketFactory().publish(
                            qos = qos,
                            topicName = topic,
                            payload = payloadValue,
                            messageExpiryInterval = messageExpiryIntervalSeconds,
                            topicAlias = topicAliasValue,
                            responseTopic = responseTopicValue,
                            correlationData = correlationDataBuffer,
                            contentType = contentTypeValue
                        )
                        scope.launch { client.publish(pub) }
                        openPublishDialog = false
                    }) {
                    Text("Queue Publish Message")
                }
            },
            dismissButton = {
                Button(
                    onClick = {
                        openPublishDialog = false
                    }) {
                    Text("Cancel Publish Message")
                }
            })
    }
}