package com.ditchoom.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.toReadBuffer
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.Topic
import com.ditchoom.mqtt3.controlpacket.ConnectionRequest

@Composable
fun ConnectionRequest4Builder(
    connect: ConnectionRequest,
    onConnectionRequestChange: (ConnectionRequest) -> Unit,
    onDoneButtonSelected: () -> Unit
) {
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
            checkBoxRow("clean session", connect.variableHeader.cleanSession) {
                onConnectionRequestChange(connect.copy(connect.variableHeader.copy(cleanSession = it)))
            }
        }
        item {
            inputTextField("Keep Alive Seconds", connect.variableHeader.keepAliveSeconds.toString()) {
                val seconds = it.toIntOrNull() ?: return@inputTextField
                onConnectionRequestChange(connect.copy(connect.variableHeader.copy(keepAliveSeconds = seconds)))
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