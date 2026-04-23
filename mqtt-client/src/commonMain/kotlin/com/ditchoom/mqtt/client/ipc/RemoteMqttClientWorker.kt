package com.ditchoom.mqtt.client.ipc

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.mqtt.client.LocalMqttClient
import com.ditchoom.mqtt.client.MqttService
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.IConnectionAcknowledgment
import com.ditchoom.mqtt.controlpacket.PublishMessage
import kotlinx.coroutines.launch

class RemoteMqttClientWorker(
    private val service: MqttService,
    internal val client: LocalMqttClient,
) {
    internal val scope = client.scope
    internal val factory = client.packetFactory

    // Registered by platform-specific IPC adapters (AndroidMqttClientIPCServer,
    // JsRemoteMqttServiceWorker). Adapters serialize the ControlPacket to a wire
    // ReadBuffer at their own boundary — the processor stays in ControlPacket space.
    internal val observers = ArrayList<(incoming: Boolean, packet: ControlPacket) -> Unit>()

    init {
        // Fan processor packet streams out to registered observers. Launched in
        // client.scope so cancellation cascades with the client lifetime.
        val processor = client.connectivityManager.processor
        scope.launch {
            processor.readChannel.collect { packet ->
                observers.forEach { it(true, packet) }
            }
        }
        scope.launch {
            processor.sentPackets.collect { packet ->
                observers.forEach { it(false, packet) }
            }
        }
    }

    suspend fun currentConnack(): IConnectionAcknowledgment? = client.currentConnectionAcknowledgment()

    fun currentConnectionAck(): IConnectionAcknowledgment? = client.connectivityManager.currentConnack()

    suspend fun awaitConnectivity(): IConnectionAcknowledgment = client.awaitConnectivity()

    suspend fun onSubscribeQueued(packetId: Int) {
        client.sendQueuedSubscribeMessage(packetId)
    }

    suspend fun onPublishQueued(
        packetId: Int,
        buffer: ReadBuffer?,
    ) {
        // Buffer arrives ready-for-read across the IPC boundary — caller used
        // pub.serialize(factory) which already called resetForRead(). A second
        // reset here would collapse limit to 0, factory.from() would underflow,
        // and the silent catch would drop the publish without surfacing the
        // error — the client then hangs forever waiting for the echo.
        //
        // The catch also hides real parse failures; a ParseException from a
        // malformed publish packet should propagate so the client sees an
        // IPC error instead of a dropped message. Removed.
        val pub0 = buffer?.let { factory.from(it) as? PublishMessage }
        client.sendQueuedPublishMessage(packetId, pub0)
    }

    suspend fun onPublishQueued(
        packetId: Int,
        pub0: PublishMessage?,
    ) {
        client.sendQueuedPublishMessage(packetId, pub0)
    }

    suspend fun onUnsubscribeQueued(packetId: Int) {
        client.sendQueuedUnsubscribeMessage(packetId)
    }

    suspend fun shutdown(sendDisconnect: Boolean) {
        client.shutdown(sendDisconnect)
        service.stop(client.broker)
    }
}
