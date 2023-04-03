package com.ditchoom.mqtt.client.ipc

import com.ditchoom.mqtt.client.LocalMqttService
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import org.w3c.dom.AbstractWorker
import org.w3c.dom.MessageChannel
import org.w3c.dom.Worker
import org.w3c.workers.ServiceWorker

private var worker: JsRemoteMqttServiceWorker? = null
suspend fun buildMqttServiceIPCServer(): JsRemoteMqttServiceWorker {
    val workerTmp = worker
    if (workerTmp != null) {
        return workerTmp
    }
    val service = LocalMqttService.buildService(null)
    val serviceServer = RemoteMqttServiceWorker(service)
    val workerLocal = JsRemoteMqttServiceWorker(serviceServer)
    worker = workerLocal
    return workerLocal
}

internal const val MESSAGE_IPC_MQTT_SERVICE_REGISTRATION = "mqtt-ipc-service-registration"
internal const val MESSAGE_IPC_MQTT_SERVICE_REGISTRATION_ACK = "mqtt-ipc-service-registration-ack"

suspend fun sendAndAwaitRegistration(worker: AbstractWorker): JsRemoteMqttServiceClient {
    val messageChannel = MessageChannel()
    if (worker is ServiceWorker) {
        worker.postMessage(MESSAGE_IPC_MQTT_SERVICE_REGISTRATION, arrayOf(messageChannel.port2))
    } else if (worker is Worker) {
        worker.postMessage(MESSAGE_IPC_MQTT_SERVICE_REGISTRATION, arrayOf(messageChannel.port2))
    }
    val ipcServer = buildMqttServiceIPCServer()
    val ipcClient = JsRemoteMqttServiceClient(ipcServer.mqttService, messageChannel.port1)
    val callbackFlow = callbackFlow {
        messageChannel.port1.onmessage = { trySend(it) }
        awaitClose()
    }
    callbackFlow.first { it.data == MESSAGE_IPC_MQTT_SERVICE_REGISTRATION_ACK }
    return ipcClient
}
