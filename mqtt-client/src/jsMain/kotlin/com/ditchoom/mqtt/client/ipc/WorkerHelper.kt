package com.ditchoom.mqtt.client.ipc

import com.ditchoom.mqtt.client.LocalMqttService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import org.w3c.dom.AbstractWorker
import org.w3c.dom.MessageChannel
import org.w3c.dom.SharedWorker
import org.w3c.dom.Worker
import org.w3c.workers.ServiceWorker

private var worker: JsRemoteMqttServiceWorker? = null

suspend fun buildMqttServiceIPCServer(useSharedMemory: Boolean): JsRemoteMqttServiceWorker {
    val workerTmp = worker
    if (workerTmp != null) {
        return workerTmp
    }
    val service = LocalMqttService.buildService(null)
    service.useSharedMemory = useSharedMemory
    val serviceServer = RemoteMqttServiceWorker(service)
    val workerLocal = JsRemoteMqttServiceWorker(serviceServer)
    worker = workerLocal
    return workerLocal
}

internal const val MESSAGE_IPC_MQTT_SERVICE_REGISTRATION = "mqtt-ipc-service-registration"
internal const val MESSAGE_IPC_MQTT_SERVICE_REGISTRATION_ACK = "mqtt-ipc-service-registration-ack"

suspend fun sendAndAwaitRegistration(worker: AbstractWorker): JsRemoteMqttServiceClient {
    val messageChannel = MessageChannel()
    when (worker) {
        is Worker -> {
            worker.postMessage(MESSAGE_IPC_MQTT_SERVICE_REGISTRATION, arrayOf(messageChannel.port2))
        }

        is ServiceWorker -> {
            worker.postMessage(MESSAGE_IPC_MQTT_SERVICE_REGISTRATION, arrayOf(messageChannel.port2))
        }

        is SharedWorker -> {
            worker.onerror = {
                console.error("worker message error", it)
            }
            worker.port.onmessage = {
                console.log("incoming shared worker message", it)
            }
            worker.port.postMessage(MESSAGE_IPC_MQTT_SERVICE_REGISTRATION, arrayOf(messageChannel.port2))
        }
    }
    val ipcServer = buildMqttServiceIPCServer(false)
    val client = JsRemoteMqttServiceClient(ipcServer.mqttService, messageChannel.port1)
    client.channel.receiveAsFlow().first { it.data == MESSAGE_IPC_MQTT_SERVICE_REGISTRATION_ACK }
    return client
}
