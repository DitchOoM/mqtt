package com.ditchoom.mqtt.client.ipc

import android.util.Log
import com.ditchoom.mqtt.client.LocalMqttService
import kotlinx.coroutines.launch

class AndroidRemoteMqttServiceWorker(private val serviceServer: RemoteMqttServiceWorker) : IPCMqttService.Stub() {
    private val scope = serviceServer.service.scope

    constructor(service: LocalMqttService) : this(RemoteMqttServiceWorker(service))

    override fun startAll(callback: OnMqttCompletionCallback) = wrapResultWithCallback(callback) {
        serviceServer.startAll()
    }

    override fun start(brokerId: Int, protocolVersion: Byte, callback: OnMqttCompletionCallback) =
        wrapResultWithCallback(callback) { serviceServer.start(brokerId, protocolVersion) }

    override fun stopAll(callback: OnMqttCompletionCallback) = wrapResultWithCallback(callback) {
        serviceServer.stopAll()
    }

    override fun stop(brokerId: Int, protocolVersion: Byte, callback: OnMqttCompletionCallback) =
        wrapResultWithCallback(callback) { serviceServer.stop(brokerId, protocolVersion) }

    private fun wrapResultWithCallback(callback: OnMqttCompletionCallback, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
                callback.onSuccess()
            } catch (e: Exception) {
                Log.e("Remote Failure", "Failed to execute remote command: ", e)
                callback.onError(e.message)
            }
        }
    }

    override fun requestClientOrNull(brokerId: Int, protocolVersion: Byte, callback: OnMqttGetClientCallback) {
        scope.launch {
            val client = serviceServer.requestClientOrNull(brokerId, protocolVersion)
            if (client != null) {
                callback.onClientReady(AndroidMqttClientIPCServer(client), brokerId, protocolVersion)
            } else {
                callback.onClientNotFound()
            }
        }
    }
}
