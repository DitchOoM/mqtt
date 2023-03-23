package com.ditchoom.mqtt.client.ipc

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.mqtt.client.MqttService
import com.ditchoom.mqtt.connection.MqttBroker
import com.ditchoom.mqtt.connection.MqttConnectionOptions
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.IConnectionRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

class IPCMqttServiceClient(
    private val scope: CoroutineScope,
    private val androidContext: Any?,
    private val emitMessage: suspend (IPCMqttMessage) -> Unit
) {
    private val clients = HashMap<Int, MutableMap<Int, IPCMqttClient>>()

    fun addBroker(
        connectionOps: Collection<MqttConnectionOptions>,
        connectionRequest: IConnectionRequest
    ) = scope.async {
        val service = getService()
        val broker = service.addMqttBroker(connectionOps, connectionRequest)
        val ipcMessage = IPCMqttMessage.BrokerAdded(broker.identifier, connectionRequest.protocolVersion)
        emitMessage(ipcMessage)
        broker
    }

    fun removeAllBrokersAndStop() = scope.launch {
        emitMessage(IPCMqttMessage.RemoveAllBrokersAndStop())
    }

    suspend fun getClient(broker: MqttBroker): IPCMqttClient? {
        val brokerLocal = getService().getPersistence(broker).brokerWithId(broker.identifier) ?: return null
        return clients
            .getOrPut(brokerLocal.identifier) { HashMap() }
            .getOrPut(brokerLocal.connectionRequest.protocolVersion) {
                val persistence = getService().getPersistence(brokerLocal)
                IPCMqttClient(scope, brokerLocal, persistence, emitMessage)
            }
    }

    fun onIncomingMessage(m: IPCMqttMessage) = scope.launch {
        when (m) {
            is IPCMqttMessage.ControlPacketReceived -> {
                findClient(m.brokerId, m.protocolVersion)
                    ?.onIncomingControlPacket(buildControlPacket(m.protocolVersion, m.packetBuffer))
            }
            is IPCMqttMessage.ControlPacketSent -> {
                findClient(m.brokerId, m.protocolVersion)
                    ?.onControlPacketSent(buildControlPacket(m.protocolVersion, m.packetBuffer))
            }
            is IPCMqttMessage.Log -> {
                findClient(m.brokerId, m.protocolVersion)
                    ?.onLog(m.message)
            }
            // ignore these, these are sent the other way
            is IPCMqttMessage.BrokerAdded,
            is IPCMqttMessage.RemoveAllBrokersAndStop,
            is IPCMqttMessage.SendControlPacket,
            is IPCMqttMessage.Shutdown -> {}
        }
    }

    private fun findClient(brokerId: Int, protocolVersion: Int): IPCMqttClient? = clients[brokerId]?.get(protocolVersion)

    private fun buildControlPacket(
        protocolVersion: Int,
        packetBuffer: PlatformBuffer,
    ): ControlPacket {
        val factory = MqttService.getControlPacketFactory(protocolVersion)
        return factory.from(packetBuffer)
    }

    private suspend fun getService(): MqttService {
        val s = serviceInternal
        return if (s == null) {
            val serviceLocal = MqttService.buildService(androidContext)
            serviceInternal = serviceLocal
            serviceLocal
        } else {
            s
        }
    }
}

private var serviceInternal: MqttService? = null
