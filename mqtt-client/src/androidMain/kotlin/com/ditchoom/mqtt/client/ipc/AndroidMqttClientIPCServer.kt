package com.ditchoom.mqtt.client.ipc

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.JvmBuffer
import com.ditchoom.mqtt.client.IpcMqttServerToClientMessage
import com.ditchoom.mqtt.client.MqttClientAidl
import com.ditchoom.mqtt.client.OnMqttCompletionCallback
import com.ditchoom.mqtt.client.OnMqttMessageCallback
import kotlinx.coroutines.launch

class AndroidMqttClientIPCServer(private val clientServer: MqttClientIPCServer) : MqttClientAidl.Stub() {
    private val observers = ArrayList<IpcMqttServerToClientMessage>()
    init {
        clientServer.observers += { incoming, byte1, remaining, buffer ->
            observers.forEach {
                if (incoming) {
                    it.onControlPacketReceived(byte1.toByte(), remaining, buffer as JvmBuffer)
                } else {
                    it.onControlPacketSent(buffer as JvmBuffer)
                }
            }
        }
    }

    override fun subscribeQueued(packetIdentifier: Int, callback: OnMqttCompletionCallback) =
        wrapResultWithCallback(callback) { clientServer.onSubscribeQueued(packetIdentifier) }

    override fun publishQueued(packetIdentifier: Int, nullablleQos0Buffer: JvmBuffer?, callback: OnMqttCompletionCallback) =
        wrapResultWithCallback(callback) { clientServer.onPublishQueued(packetIdentifier, nullablleQos0Buffer) }

    override fun unsubscribeQueued(packetIdentifier: Int, callback: OnMqttCompletionCallback) =
        wrapResultWithCallback(callback) { clientServer.onUnsubscribeQueued(packetIdentifier) }

    override fun registerObserver(observer: IpcMqttServerToClientMessage) {
        observers += observer
    }

    override fun unregisterObserver(observer: IpcMqttServerToClientMessage) {
        observers -= observer
    }

    override fun currentConnectionAcknowledgmentOrNull(): JvmBuffer? {
        return clientServer.currentConnectionAck()?.serialize(AllocationZone.SharedMemory) as? JvmBuffer
    }

    override fun awaitConnectivity(cb: OnMqttMessageCallback) {
        clientServer.scope.launch {
            cb.onMessage(clientServer.awaitConnectivity().serialize(AllocationZone.SharedMemory) as JvmBuffer)
        }
    }

    override fun pingCount(): Long {
        return clientServer.client.connectivityManager.processor.pingCount
    }

    override fun pingResponseCount(): Long {
        return clientServer.client.connectivityManager.processor.pingResponseCount
    }

    override fun connectionCount(): Long {
        return clientServer.client.connectivityManager.connectionCount
    }

    override fun connectionAttempts(): Long {
        return clientServer.client.connectivityManager.connectionAttempts
    }

    override fun sendDisconnect(cb: OnMqttCompletionCallback) =
        wrapResultWithCallback(cb) { clientServer.client.sendDisconnect() }

    override fun shutdown(sendDisconnect: Boolean, cb: OnMqttCompletionCallback) =
        wrapResultWithCallback(cb) { clientServer.shutdown(sendDisconnect) }
    private fun wrapResultWithCallback(callback: OnMqttCompletionCallback, block: suspend () -> Unit) {
        clientServer.scope.launch {
            try {
                block()
                callback.onSuccess()
            } catch (e: Exception) {
                callback.onError(e.message)
            }
        }
    }
}
