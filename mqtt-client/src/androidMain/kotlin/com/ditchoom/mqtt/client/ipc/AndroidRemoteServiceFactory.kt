package com.ditchoom.mqtt.client.ipc

import android.content.Context
import com.ditchoom.mqtt.client.MqttService

actual suspend fun remoteMqttServiceWorkerClient(
    androidContextOrAbstractWorker: Any?,
    inMemory: Boolean
): MqttService? =
    MqttServiceHelper.registerService(androidContextOrAbstractWorker as Context, inMemory)
