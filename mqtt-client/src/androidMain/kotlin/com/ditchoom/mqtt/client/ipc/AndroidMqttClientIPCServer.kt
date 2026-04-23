package com.ditchoom.mqtt.client.ipc

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.JvmBuffer
import com.ditchoom.buffer.shared
import com.ditchoom.mqtt.controlpacket.encoding.readVariableByteInteger
import kotlinx.coroutines.launch

class AndroidMqttClientIPCServer(
    private val clientServer: RemoteMqttClientWorker,
) : IPCMqttClient.Stub() {
    private val observers = HashMap<Int, MqttMessageTransferredCallback>()
    private val publishStateObservers = HashMap<Int, MqttPublishStateCallback>()

    init {
        clientServer.observers += { incoming, packet ->
            // Skip serialization when nobody's listening — this hook fires on every
            // packet in both directions for the lifetime of the worker.
            if (observers.isNotEmpty()) {
                // serialize() returns a read-ready buffer (position=0, limit=N).
                // Do NOT resetForRead() on it — that's the double-reset bug that
                // collapses limit to 0. After each AIDL callback, the Parcel marshal
                // advances position to limit; resetForRead() between iterations
                // flips it back to read-ready for the next observer.
                val buffer = packet.serialize(BufferFactory.shared()) as JvmBuffer
                observers.values.forEach { cb ->
                    if (incoming) {
                        // AIDL contract: onControlPacketReceived takes (byte1,
                        // remainingLength, bodyBuffer). packetFactory.from(buffer,
                        // byte1, remainingLength) does not re-read the fixed header,
                        // so advance past it first.
                        buffer.readUnsignedByte()
                        buffer.readVariableByteInteger()
                        cb.onControlPacketReceived(
                            packet.byte1.toByte(),
                            packet.remainingLength(),
                            buffer,
                        )
                    } else {
                        // onControlPacketSent takes the whole wire buffer; the
                        // client-side packetFactory.from(buffer) re-reads byte1 +
                        // remainingLength.
                        cb.onControlPacketSent(buffer)
                    }
                    buffer.resetForRead()
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

    override fun registerPublishStateObserver(observer: MqttPublishStateCallback) {
        publishStateObservers[observer.hashCode()] = observer
    }

    override fun unregisterPublishStateObserver(observer: MqttPublishStateCallback) {
        publishStateObservers.remove(observer.hashCode())
    }

    /** Notify all registered state observers of a publish state change. */
    internal fun notifyPublishStateChanged(
        packetId: Int,
        state: Int,
    ) {
        publishStateObservers.values.forEach { it.onStateChanged(packetId, state) }
    }

    override fun currentConnectionAcknowledgmentOrNull(): JvmBuffer? =
        clientServer.currentConnectionAck()?.serialize(BufferFactory.shared()) as? JvmBuffer

    override fun awaitConnectivity(cb: MqttMessageCallback) {
        clientServer.scope.launch {
            cb.onMessage(clientServer.awaitConnectivity().serialize(BufferFactory.shared()) as JvmBuffer)
        }
    }

    override fun pingCount(): Long = clientServer.client.connectivityManager.processor.pingCount

    override fun pingResponseCount(): Long = clientServer.client.connectivityManager.processor.pingResponseCount

    override fun connectionCount(): Long = clientServer.client.connectivityManager.connectionCount

    override fun connectionAttempts(): Long = clientServer.client.connectivityManager.connectionAttempts

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
