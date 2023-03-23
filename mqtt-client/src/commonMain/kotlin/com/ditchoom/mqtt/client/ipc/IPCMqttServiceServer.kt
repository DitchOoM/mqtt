package com.ditchoom.mqtt.client.ipc

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.mqtt.client.MqttService
import com.ditchoom.mqtt.controlpacket.IPublishMessage
import com.ditchoom.mqtt.controlpacket.ISubscribeRequest
import com.ditchoom.mqtt.controlpacket.IUnsubscribeRequest
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

class IPCMqttServiceServer(
    private val service: MqttService,
    private val emitMessage: suspend (IPCMqttMessage) -> Unit
) {

    fun onBrokerAdded(brokerId: Int, protocolVersion: Int) = service.scope.launch {
        val persistence = service.getPersistence(protocolVersion)
        val brokerFound = persistence.brokerWithId(brokerId) ?: return@launch
        service.start(brokerFound)
    }

    fun removeAllBrokersAndStop() = service.scope.launch {
        val removeJobs = service.allMqttBrokers().map {
            service.removeBroker(it).job
        } + service.stop()
        joinAll(*removeJobs.toTypedArray())
    }

    fun onSendControlPacket(
        brokerId: Int,
        protocolVersion: Int,
        controlPacketType: Byte,
        packetId: Int,
        publishBuffer: PlatformBuffer?
    ) = service.scope.launch {
        val persistence = service.getPersistence(protocolVersion)
        val broker = persistence.brokerWithId(brokerId) ?: return@launch
        val client = service.getClient(broker) ?: return@launch
        when (controlPacketType) {
            IPublishMessage.controlPacketValue -> {
                val pub0 = publishBuffer
                    ?.let { client.controlPacketFactory().from(it) as? IPublishMessage }
                client.sendQueuedPublishMessage(packetId, pub0)
            }

            ISubscribeRequest.controlPacketValue -> {
                client.sendQueuedSubscribeMessage(packetId)
            }

            IUnsubscribeRequest.controlPacketValue -> {
                client.sendQueuedUnsubscribeMessage(packetId)
            }
            else -> {}
        }
    }

    fun emitControlPacketSent(
        brokerId: Int,
        protocolVersion: Int,
        controlPacketType: Byte,
        packetId: Int,
        buffer: PlatformBuffer
    ) = service.scope.launch {
        emitMessage(IPCMqttMessage.ControlPacketSent(brokerId, protocolVersion, controlPacketType, packetId, buffer))
    }

    fun emitControlPacketReceived(
        brokerId: Int,
        protocolVersion: Int,
        controlPacketType: Byte,
        packetId: Int,
        buffer: PlatformBuffer
    ) = service.scope.launch {
        emitMessage(IPCMqttMessage.ControlPacketReceived(brokerId, protocolVersion, controlPacketType, packetId, buffer))
    }

    fun shutdown(brokerId: Int, protocolVersion: Int) = service.scope.launch {
        val persistence = service.getPersistence(protocolVersion)
        val broker = persistence.brokerWithId(brokerId) ?: return@launch
        val client = service.getClient(broker) ?: return@launch
        client.shutdown()
    }
}
