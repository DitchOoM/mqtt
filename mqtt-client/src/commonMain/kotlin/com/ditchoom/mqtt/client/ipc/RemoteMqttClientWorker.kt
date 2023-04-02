package com.ditchoom.mqtt.client.ipc

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.mqtt.client.LocalMqttClient
import com.ditchoom.mqtt.controlpacket.IConnectionAcknowledgment
import com.ditchoom.mqtt.controlpacket.IPublishMessage

class RemoteMqttClientWorker(
    internal val client: LocalMqttClient,
) {
    internal val scope = client.scope
    internal val factory = client.packetFactory
    internal val observers = ArrayList<(Boolean, UByte, Int, ReadBuffer) -> Unit>()

    init {
        client.allocateSharedMemory = true
    }

    suspend fun currentConnack(): IConnectionAcknowledgment? = client.currentConnectionAcknowledgment()
    fun currentConnectionAck(): IConnectionAcknowledgment? = client.connectivityManager.currentConnack()
    suspend fun awaitConnectivity(): IConnectionAcknowledgment = client.awaitConnectivity()

    suspend fun onSubscribeQueued(packetId: Int) {
        client.sendQueuedSubscribeMessage(packetId)
    }

    suspend fun onPublishQueued(packetId: Int, buffer: ReadBuffer?) {
        val pub0 = buffer?.let {
            it.resetForRead()
            factory.from(it) as? IPublishMessage
        }
        client.sendQueuedPublishMessage(packetId, pub0)
    }

    suspend fun onPublishQueued(packetId: Int, pub0: IPublishMessage?) {
        client.sendQueuedPublishMessage(packetId, pub0)
    }

    suspend fun onUnsubscribeQueued(packetId: Int) {
        client.sendQueuedUnsubscribeMessage(packetId)
    }

    suspend fun shutdown(sendDisconnect: Boolean) {
        client.shutdown(sendDisconnect)
    }
}
