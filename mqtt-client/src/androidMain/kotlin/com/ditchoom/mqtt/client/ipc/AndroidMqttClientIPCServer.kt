package com.ditchoom.mqtt.client.ipc

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.JvmBuffer
import kotlinx.coroutines.launch

class AndroidMqttClientIPCServer(private val clientServer: RemoteMqttClientWorker) : IPCMqttClient.Stub() {
    private val observers = HashMap<Int, MqttMessageTransferredCallback>()

    init {
        clientServer.observers += { incoming, byte1, remaining, buffer ->
            observers.values.forEach {
                if (incoming) {
                    it.onControlPacketReceived(byte1.toByte(), remaining, buffer as JvmBuffer)
                    buffer.resetForRead()
                } else {
                    it.onControlPacketSent(buffer as JvmBuffer)
                }
            }
        }
    }

    override fun subscribeQueued(
        packetIdentifier: Int,
        callback: MqttCompletionCallback,
    ) = wrapResultWithCallback(callback) { clientServer.onSubscribeQueued(packetIdentifier) }

    override fun publishQueued(
        packetIdentifier: Int,
        nullablleQos0Buffer: JvmBuffer?,
        callback: MqttCompletionCallback,
    ) = wrapResultWithCallback(callback) { clientServer.onPublishQueued(packetIdentifier, nullablleQos0Buffer) }

    override fun unsubscribeQueued(
        packetIdentifier: Int,
        callback: MqttCompletionCallback,
    ) = wrapResultWithCallback(callback) { clientServer.onUnsubscribeQueued(packetIdentifier) }

    override fun registerObserver(observer: MqttMessageTransferredCallback) {
        observers[observer.id()] = observer
    }

    override fun unregisterObserver(observer: MqttMessageTransferredCallback) {
        observers.remove(observer.id())
    }

    override fun currentConnectionAcknowledgmentOrNull(): JvmBuffer? {
        return clientServer.currentConnectionAck()?.serialize(AllocationZone.SharedMemory) as? JvmBuffer
    }

    override fun awaitConnectivity(cb: MqttMessageCallback) {
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

    override fun sendDisconnect(cb: MqttCompletionCallback) = wrapResultWithCallback(cb) { clientServer.client.sendDisconnect() }

    override fun shutdown(
        sendDisconnect: Boolean,
        cb: MqttCompletionCallback,
    ) = wrapResultWithCallback(cb) {
        clientServer.shutdown(sendDisconnect)
    }

    private fun wrapResultWithCallback(
        callback: MqttCompletionCallback,
        block: suspend () -> Unit,
    ) {
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
