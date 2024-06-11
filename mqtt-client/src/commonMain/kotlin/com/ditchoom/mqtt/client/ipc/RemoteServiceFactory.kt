package com.ditchoom.mqtt.client.ipc

import com.ditchoom.mqtt.client.MqttService

expect suspend fun remoteMqttServiceWorkerClient(
    androidContextOrAbstractWorker: Any?,
    inMemory: Boolean,
): MqttService?
