package com.ditchoom.mqtt.client.ipc

import com.ditchoom.mqtt.client.LocalMqttService
import com.ditchoom.mqtt.connection.MqttBroker

class RemoteMqttServiceWorker(
    internal val service: LocalMqttService,
) {
    private val clients = HashMap<Byte, HashMap<Int, RemoteMqttClientWorker>>()

    init {
        service.incomingMessages = { broker, byte1, remaining, buffer ->
            clients[broker.connectionRequest.protocolVersion.toByte()]
                ?.get(broker.identifier)
                ?.observers?.forEach {
                    it(true, byte1, remaining, buffer)
                }
        }
        service.sentMessages = { broker, buffer ->
            clients[broker.connectionRequest.protocolVersion.toByte()]
                ?.get(broker.identifier)
                ?.observers?.forEach {
                    it(false, 0u, 0, buffer)
                }
        }
    }

    private suspend fun findBroker(brokerId: Int, protocolVersion: Byte): MqttBroker? {
        val persistence = service.getPersistence(protocolVersion)
        return persistence.brokerWithId(brokerId)
    }

    suspend fun startAll() {
        service.start()
    }

    suspend fun start(brokerId: Int, protocolVersion: Byte) {
        val brokerFound = findBroker(brokerId, protocolVersion) ?: return
        service.start(brokerFound)
    }

    suspend fun stop(brokerId: Int, protocolVersion: Byte) {
        val brokerFound = findBroker(brokerId, protocolVersion) ?: return
        service.stop(brokerFound)
        clients[protocolVersion]?.remove(brokerId)
    }

    suspend fun stopAll() {
        service.stop()
        clients.clear()
    }

    suspend fun requestClientOrNull(brokerId: Int, protocolVersion: Byte): RemoteMqttClientWorker? {
        val cached = clients[protocolVersion]?.get(brokerId)
        if (cached != null && !cached.client.isStopped()) {
            return cached
        }
        val persistence = service.getPersistence(protocolVersion)
        val broker = persistence.brokerWithId(brokerId) ?: return null
        val client = service.getClient(broker) ?: return null
        val ipcClient = RemoteMqttClientWorker(service, client)
        clients.getOrPut(protocolVersion) { HashMap() }[brokerId] = ipcClient
        return ipcClient
    }
}
