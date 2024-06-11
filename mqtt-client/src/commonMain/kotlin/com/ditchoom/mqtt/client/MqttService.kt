package com.ditchoom.mqtt.client

import com.ditchoom.mqtt.client.ipc.remoteMqttServiceWorkerClient
import com.ditchoom.mqtt.connection.MqttBroker
import com.ditchoom.mqtt.connection.MqttConnectionOptions
import com.ditchoom.mqtt.controlpacket.IConnectionRequest

interface MqttService {
    suspend fun addBroker(
        connectionOps: Collection<MqttConnectionOptions>,
        connectionRequest: IConnectionRequest,
    ): MqttBroker

    suspend fun allBrokers(): Collection<MqttBroker>

    suspend fun removeBroker(
        brokerId: Int,
        protocolVersion: Byte,
    )

    suspend fun addBrokerAndStartClient(
        connectionOps: MqttConnectionOptions,
        connectionRequest: IConnectionRequest,
    ): MqttClient {
        val broker = addBroker(listOf(connectionOps), connectionRequest)
        start(broker)
        return checkNotNull(getClient(broker))
    }

    suspend fun addBrokerAndStartClient(
        connectionOps: Collection<MqttConnectionOptions>,
        connectionRequest: IConnectionRequest,
    ): MqttClient {
        val broker = addBroker(connectionOps, connectionRequest)
        start(broker)
        return checkNotNull(getClient(broker))
    }

    suspend fun getClient(broker: MqttBroker): MqttClient?

    suspend fun start(broker: MqttBroker)

    suspend fun start()

    suspend fun stop()

    suspend fun stop(broker: MqttBroker)

    companion object {
        suspend fun buildNewService(
            ipcEnabled: Boolean,
            androidContextOrAbstractWorker: Any? = null,
            inMemory: Boolean = false,
        ): MqttService {
            var serviceFound: MqttService? = null
            if (ipcEnabled) {
                serviceFound = remoteMqttServiceWorkerClient(androidContextOrAbstractWorker, inMemory)
            }
            if (serviceFound == null) {
                return LocalMqttService.buildService(androidContextOrAbstractWorker, inMemory)
            }

            return serviceFound
        }
    }
}
