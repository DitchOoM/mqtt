package com.ditchoom.mqtt.connection

import com.ditchoom.mqtt.controlpacket.IConnectionRequest

data class MqttBroker(
    val identifier: Int,
    val connectionOps: Collection<MqttConnectionOptions>,
    val connectionRequest: IConnectionRequest
) {
    val brokerId = identifier
    val protocolVersion = connectionRequest.protocolVersion.toByte()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as MqttBroker

        if (identifier != other.identifier) return false
        if (connectionOps != other.connectionOps) return false
        if (connectionRequest != other.connectionRequest) return false

        return true
    }

    override fun hashCode(): Int {
        var result = identifier
        result = 31 * result + connectionOps.hashCode()
        result = 31 * result + connectionRequest.hashCode()
        return result
    }
}
