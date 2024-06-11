@file:Suppress("UNCHECKED_CAST_TO_EXTERNAL_INTERFACE")

package com.ditchoom.mqtt.client.ipc

import com.ditchoom.mqtt.client.MqttService
import com.ditchoom.socket.NetworkCapabilities
import com.ditchoom.socket.getNetworkCapabilities
import org.w3c.dom.AbstractWorker

actual suspend fun remoteMqttServiceWorkerClient(
    androidContextOrAbstractWorker: Any?,
    inMemory: Boolean,
): MqttService? =
    if (getNetworkCapabilities() == NetworkCapabilities.WEBSOCKETS_ONLY) {
        sendAndAwaitRegistration(androidContextOrAbstractWorker as AbstractWorker)
    } else {
        null
    }
