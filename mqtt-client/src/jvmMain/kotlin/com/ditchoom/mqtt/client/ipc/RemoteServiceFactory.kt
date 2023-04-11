package com.ditchoom.mqtt.client.ipc

import com.ditchoom.mqtt.client.MqttService

actual suspend fun remoteMqttServiceWorkerClient(
    androidContextOrAbstractWorker: Any?,
    inMemory: Boolean
): MqttService? = null
