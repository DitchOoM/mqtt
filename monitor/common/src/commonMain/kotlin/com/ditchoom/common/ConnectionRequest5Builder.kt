package com.ditchoom.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.toReadBuffer
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.Topic
import com.ditchoom.mqtt5.controlpacket.ConnectionRequest

@Composable
fun ConnectionRequest5Builder(
    connect: ConnectionRequest,
    onConnectionRequestChange: (ConnectionRequest) -> Unit,
    onDoneButtonSelected: () -> Unit
) {
    var showMqtt5Properties by remember { mutableStateOf(false) }
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        item {
            inputTextField("Client ID", connect.payload.clientId) {
                onConnectionRequestChange(connect.copy(payload = connect.payload.copy(clientId = it)))
            }
        }
        item {
            checkBoxRow("has username", connect.variableHeader.hasUserName) {
                onConnectionRequestChange(connect.copy(variableHeader = connect.variableHeader.copy(hasUserName = it)))
            }
        }
        if (connect.variableHeader.hasUserName) {
            item {
                inputTextField("Username", connect.payload.userName ?: "") {
                    onConnectionRequestChange(connect.copy(payload = connect.payload.copy(userName = it)))
                }
            }
        }
        item {
            checkBoxRow("has password", connect.variableHeader.hasPassword) {
                onConnectionRequestChange(connect.copy(variableHeader = connect.variableHeader.copy(hasPassword = it)))
            }
        }
        if (connect.variableHeader.hasPassword) {
            item {
                inputTextField("Password", connect.payload.password ?: "") {
                    onConnectionRequestChange(connect.copy(payload = connect.payload.copy(password = it)))
                }
            }
        }
        item {
            checkBoxRow("include will", connect.variableHeader.willFlag) {
                onConnectionRequestChange(connect.copy(variableHeader = connect.variableHeader.copy(willFlag = it)))
            }
        }
        if (connect.variableHeader.willFlag) {
            item {
                inputTextField("Will Topic", connect.payload.willTopic.toString()) {
                    onConnectionRequestChange(
                        connect.copy(payload = connect.payload.copy(willTopic = Topic.fromOrThrow(it, Topic.Type.Name)))
                    )
                }
            }
            item {
                val payload = connect.payload.willPayload?.slice()
                val string = payload?.readString(payload.remaining()) ?: ""
                inputTextField("Will Payload", string) {
                    onConnectionRequestChange(
                        connect.copy(payload = connect.payload.copy(willPayload = it.toReadBuffer(Charset.UTF8)))
                    )
                }
            }
            item {
                checkBoxRow("Will Retain", connect.variableHeader.willRetain) {
                    onConnectionRequestChange(connect.copy(variableHeader = connect.variableHeader.copy(willRetain = it)))
                }
            }
            item {
                inputTextField("Will Qos", connect.variableHeader.willQos.integerValue.toString()) {
                    val qos = when (it) {
                        "0" -> QualityOfService.AT_MOST_ONCE
                        "1" -> QualityOfService.AT_LEAST_ONCE
                        "2" -> QualityOfService.EXACTLY_ONCE
                        else -> return@inputTextField
                    }
                    onConnectionRequestChange(
                        connect.copy(variableHeader = connect.variableHeader.copy(willQos = qos))
                    )
                }
            }
        }
        item {
            checkBoxRow("clean start", connect.variableHeader.cleanStart) {
                onConnectionRequestChange(connect.copy(connect.variableHeader.copy(cleanStart = it)))
            }
        }
        item {
            inputTextField("Keep Alive Seconds", connect.variableHeader.keepAliveSeconds.toString()) {
                val seconds = it.toIntOrNull() ?: return@inputTextField
                onConnectionRequestChange(connect.copy(connect.variableHeader.copy(keepAliveSeconds = seconds)))
            }
        }
        item {
            checkBoxRow("MQTT 5 Extra Properties", showMqtt5Properties) { showMqtt5Properties = it }
        }
        if (showMqtt5Properties) {
            item {
                inputTextField(
                    "Session Expiry Interval Seconds",
                    connect.variableHeader.properties.sessionExpiryIntervalSeconds?.toString() ?: "0"
                ) {
                    val seconds = it.toULongOrNull() ?: return@inputTextField
                    onConnectionRequestChange(
                        connect.copy(
                            variableHeader = connect.variableHeader.copy(
                                properties = connect.variableHeader.properties.copy(sessionExpiryIntervalSeconds = seconds)
                            )
                        )
                    )
                }
            }
            item {
                inputTextField(
                    "Receive Maximum",
                    connect.variableHeader.properties.receiveMaximum?.toString() ?: ""
                ) {
                    val receiveMax = it.toUShortOrNull()?.toInt() ?: return@inputTextField
                    onConnectionRequestChange(
                        connect.copy(
                            variableHeader = connect.variableHeader.copy(
                                properties = connect.variableHeader.properties.copy(receiveMaximum = receiveMax)
                            )
                        )
                    )
                }
            }
            item {
                inputTextField(
                    "Max Packet Size",
                    connect.variableHeader.properties.maximumPacketSize?.toString() ?: "2048"
                ) {
                    val maximumPacketSize = it.toULongOrNull() ?: return@inputTextField
                    onConnectionRequestChange(
                        connect.copy(
                            variableHeader = connect.variableHeader.copy(
                                properties = connect.variableHeader.properties.copy(maximumPacketSize = maximumPacketSize)
                            )
                        )
                    )
                }
            }
            item {
                inputTextField(
                    "Topic Alias Max",
                    connect.variableHeader.properties.topicAliasMaximum?.toString() ?: "100"
                ) {
                    val topicAliasMaximum = it.toUShortOrNull()?.toInt() ?: return@inputTextField
                    onConnectionRequestChange(
                        connect.copy(
                            variableHeader = connect.variableHeader.copy(
                                properties = connect.variableHeader.properties.copy(topicAliasMaximum = topicAliasMaximum)
                            )
                        )
                    )
                }
            }
            item {
                checkBoxRow(
                    "Request Response Information",
                    connect.variableHeader.properties.requestResponseInformation ?: false
                ) {
                    onConnectionRequestChange(
                        connect.copy(
                            variableHeader = connect.variableHeader.copy(
                                properties = connect.variableHeader.properties.copy(requestResponseInformation = it)
                            )
                        )
                    )
                }
            }
            item {
                checkBoxRow(
                    "Request Problem Information",
                    connect.variableHeader.properties.requestProblemInformation ?: false
                ) {
                    onConnectionRequestChange(
                        connect.copy(
                            variableHeader = connect.variableHeader.copy(
                                properties = connect.variableHeader.properties.copy(requestProblemInformation = it)
                            )
                        )
                    )
                }
            }
        }
        item {
            Button(onClick = {
                onDoneButtonSelected()
            }) {
                Text("Done")
            }
        }
    }
}