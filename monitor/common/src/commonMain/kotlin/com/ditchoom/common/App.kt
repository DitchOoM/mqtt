package com.ditchoom.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.Button
import androidx.compose.material.Checkbox
import androidx.compose.material.Text
import androidx.compose.material.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.ditchoom.mqtt.client.LocalMqttService
import com.ditchoom.mqtt.client.MqttClient
import com.ditchoom.mqtt.client.MqttService
import com.ditchoom.mqtt.connection.MqttBroker
import com.ditchoom.mqtt.connection.MqttConnectionOptions
import com.ditchoom.mqtt5.controlpacket.ConnectionRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.random.nextUInt
import kotlin.time.Duration.Companion.seconds

@Composable
fun App(service: LocalMqttService) {
    var brokers by remember { mutableStateOf<List<MqttBroker>>(mutableListOf()) }
    var selectedClient by remember { mutableStateOf<MqttClient?>(null) }
    var mqttLogs by remember { mutableStateOf("") }
    val selectedClientLocal = selectedClient
    LaunchedEffect(service) {
        service.assignObservers(LoggingObserver { brokerId, log ->
            mqttLogs += "$log\r\n"
        })
        brokers = service.allBrokers().toList()
    }
    if (selectedClientLocal != null) {
        MqttConnectionViewer(selectedClientLocal, mqttLogs) {
            selectedClient = null
        }
    } else if (brokers.isNotEmpty()) {
//        service.start()
        showBrokers(service, brokers.toList()) {
            selectedClient = it
        }
    } else {
        ConnectionBuilder(service, mqttLogs) {
            if (it != null) {
                brokers = brokers + it.broker
                println("\r\nadd broker $it")
                selectedClient = it
            }
        }
    }
}

@Composable
fun showBrokers(
    service: MqttService,
    brokers: List<MqttBroker>,
    onBrokerSelected: (MqttClient) -> Unit
) {
    val scope = rememberCoroutineScope()
    LazyColumn {
        items(brokers) {
            Button(onClick = {
                scope.launch {
                    val client = service.getClient(it) ?: return@launch
                    onBrokerSelected(client)
                }
            }) {
                Text(it.connectionOps.joinToString() + "\r\n" + it.connectionRequest.toString())
            }
        }
    }
}


@Composable
fun ConnectionBuilder(service: LocalMqttService, mqttLogs: String, onBrokerSelected: (MqttClient?) -> Unit) {
    val platformName = getPlatformName()
    var mqttVersionPicked by remember { mutableStateOf(false) }
    var mqttVersion by remember { mutableStateOf(4) }
    var connectionOptions by remember {
        mutableStateOf<MqttConnectionOptions>(
            MqttConnectionOptions.SocketConnection(
                if (platformName == "Android") "10.0.2.2" else "localhost",
                1883,
                false,
                5.seconds,
                25.seconds,
                25.seconds
            )
        )
    }
    var connectionOptionComplete by remember { mutableStateOf(false) }
    var connectionRequest4 by remember {
        mutableStateOf(
            com.ditchoom.mqtt3.controlpacket.ConnectionRequest(
                clientId = "meow${Random.nextUInt()}-$platformName",
                cleanSession = true,
                keepAliveSeconds = 15
            )
        )
    }
    var connectionRequest5 by remember {
        mutableStateOf(
            ConnectionRequest(
                clientId = "meow${Random.nextUInt()}-$platformName",
                cleanStart = true,
                keepAliveSeconds = 15
            )
        )
    }
    var connectionRequestComplete by remember { mutableStateOf(-1) }
    if (!mqttVersionPicked) {
        Column {
            inputTextField("Mqtt Version", mqttVersion.toString()) {
                mqttVersion = it.toIntOrNull() ?: return@inputTextField
            }
            Button(onClick = {
                mqttVersionPicked = true
            }) {
                Text("Done")
            }
        }
    } else if (!connectionOptionComplete) {
        MqttConnection(mqttVersion, connectionOptions,
            onConnectionOptionChange = {
                connectionOptions = it
            }) {
            connectionOptionComplete = true
        }
    } else if (connectionRequestComplete == -1) {
        if (mqttVersion == 5) {
            ConnectionRequest5Builder(connectionRequest5, { connectionRequest5 = it }) {
                connectionRequestComplete = 5
            }
        } else {
            ConnectionRequest4Builder(connectionRequest4, { connectionRequest4 = it }) {
                connectionRequestComplete = 4
            }
        }
    } else {
        // done!
        val connect = if (mqttVersion == 5) {
            connectionRequest5
        } else {
            connectionRequest4
        }
        val options = connectionOptions
        LaunchedEffect(connect, options) {
            println("\r\nlaunch")
            val persistedBroker = service.addBroker(listOf(connectionOptions), connect)
            service.start()
            val client = service.getClient(persistedBroker) ?: return@LaunchedEffect
            launch(Dispatchers.Main) {
                onBrokerSelected(client)
            }
        }
    }
}

@Composable
fun MqttConnection(
    mqttVersion: Int, connectionOptions: MqttConnectionOptions,
    onConnectionOptionChange: (MqttConnectionOptions) -> Unit,
    connectionOptionComplete: () -> Unit
) {
    LazyColumn {
        item {
            checkBoxRow("tls", connectionOptions.tls) {
                onConnectionOptionChange(connectionOptions.copy(tls = it))
            }
        }
        item {
            inputTextField("Host", connectionOptions.host) {
                onConnectionOptionChange(connectionOptions.copy(host = it))
            }
        }
        item {
            inputTextField("Port", connectionOptions.port.toString()) {
                val value = it.toIntOrNull() ?: return@inputTextField
                onConnectionOptionChange(connectionOptions.copy(port = value))
            }
        }
        item {
            inputTextField(
                "connection timeout seconds",
                connectionOptions.connectionTimeout.inWholeSeconds.toString()
            ) {
                val value = it.toIntOrNull()?.seconds ?: return@inputTextField
                onConnectionOptionChange(connectionOptions.copy(connectionTimeout = value))
            }
        }
        item {
            checkBoxRow("is websocket", connectionOptions is MqttConnectionOptions.WebSocketConnectionOptions) {
                onConnectionOptionChange(connectionOptions.copy(isWebsocket = it))
            }
        }
        if (connectionOptions is MqttConnectionOptions.WebSocketConnectionOptions) {
            item {
                val mqttProtocol = if (mqttVersion == 5) "mqttv5" else "mqttv3.1"
                inputTextField("websocket protocol", connectionOptions.protocols.firstOrNull() ?: mqttProtocol) {
                    onConnectionOptionChange((connectionOptions as MqttConnectionOptions).copy(protocols = listOf(it)))
                }
            }
            item {
                inputTextField("websocket endpoint", connectionOptions.websocketEndpoint) {
                    onConnectionOptionChange((connectionOptions as MqttConnectionOptions).copy(websocketEndpoint = it))
                }
            }
        }
        item {
            Button(onClick = {
                connectionOptionComplete()
            }) {
                Text("Done")
            }
        }
    }
}

@Composable
fun checkBoxRow(name: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row {
        Checkbox(value, onCheckedChange = { onChange(it) })
        Text("$name = $value", modifier = Modifier.align(Alignment.CenterVertically))
    }
}

@Composable
fun inputTextField(name: String, value: String, isDecimalKeyboard: Boolean = false, onChange: (String) -> Unit) {
    val options = if (isDecimalKeyboard) {
        KeyboardOptions(keyboardType = KeyboardType.Decimal)
    } else {
        KeyboardOptions.Default
    }
    TextField(
        value = value,
        modifier = Modifier.fillMaxWidth(),
        onValueChange = { onChange(it) },
        keyboardOptions = options,
        label = { Text(name) }
    )
}