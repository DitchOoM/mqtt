package com.ditchoom.mqtt.client.ipc

import com.ditchoom.mqtt.client.LocalMqttService
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import org.w3c.dom.AbstractWorker
import org.w3c.dom.MessageChannel
import org.w3c.dom.Worker
import org.w3c.workers.ServiceWorker

suspend fun buildMqttServiceIPCServer(): JsRemoteMqttServiceWorker {
    val service = LocalMqttService.buildService(null)
    val serviceServer = RemoteMqttServiceWorker(service)
    return JsRemoteMqttServiceWorker(serviceServer)
}

const val BROADCAST_IPC_MQTT_SERVICE = "mqtt-ipc-service"
internal const val MESSAGE_IPC_MQTT_SERVICE_REGISTRATION = "mqtt-ipc-service-registration"
internal const val MESSAGE_IPC_MQTT_SERVICE_REGISTRATION_ACK = "mqtt-ipc-service-registration-ack"
internal const val MESSAGE_IPC_MQTT_SERVICE_CLOSE_CLIENT = "mqtt-ipc-service-close-client"

suspend fun AbstractWorker.sendAndAwaitRegistration(ipcServer: JsRemoteMqttServiceWorker): JsRemoteMqttServiceClient {
    val messageChannel = MessageChannel()
    if (this is ServiceWorker) {
        postMessage(MESSAGE_IPC_MQTT_SERVICE_REGISTRATION, arrayOf(messageChannel.port2))
    } else if (this is Worker) {
        postMessage(MESSAGE_IPC_MQTT_SERVICE_REGISTRATION, arrayOf(messageChannel.port2))
    }
    val ipcClient = JsRemoteMqttServiceClient(ipcServer.mqttService, messageChannel.port1)
    val callbackFlow = callbackFlow {
        messageChannel.port1.onmessage = { trySend(it) }
        awaitClose()
    }
    callbackFlow.first { it.data == MESSAGE_IPC_MQTT_SERVICE_REGISTRATION_ACK }
    return ipcClient
}
