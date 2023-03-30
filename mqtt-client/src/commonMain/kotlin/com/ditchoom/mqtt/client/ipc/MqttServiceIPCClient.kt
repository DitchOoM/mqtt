package com.ditchoom.mqtt.client.ipc

import com.ditchoom.mqtt.client.LocalMqttService
import com.ditchoom.mqtt.client.MqttService
import com.ditchoom.mqtt.connection.MqttBroker
import com.ditchoom.mqtt.connection.MqttConnectionOptions
import com.ditchoom.mqtt.controlpacket.IConnectionRequest

abstract class MqttServiceIPCClient(
    protected val service: LocalMqttService,
    protected open val startAllCb: suspend () -> Unit = {},
    protected open val startCb: suspend (Int, Byte) -> Unit = { _, _ -> },
    protected open val stopAllCb: suspend () -> Unit = {},
    protected open val stopCb: suspend (Int, Byte) -> Unit = { _, _ -> },
) : MqttService {
    protected val scope = service.scope

    override suspend fun addBroker(
        connectionOps: Collection<MqttConnectionOptions>,
        connectionRequest: IConnectionRequest
    ): MqttBroker {
        val persistence = service.getPersistence(connectionRequest)
        return persistence.addBroker(connectionOps, connectionRequest)
    }

    override suspend fun allBrokers() = service.allBrokers()

    override suspend fun removeBroker(brokerId: Int, protocolVersion: Byte) {
        service.removeBroker(brokerId, protocolVersion)
    }

    override suspend fun start() { startAllCb() }

    override suspend fun start(broker: MqttBroker) {
        startCb(broker.brokerId, broker.protocolVersion)
    }

    override suspend fun stop() {
        stopAllCb()
    }

    override suspend fun stop(broker: MqttBroker) {
        stopCb(broker.brokerId, broker.protocolVersion)
    }
}
