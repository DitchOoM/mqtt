package com.ditchoom.mqtt.connection

import com.ditchoom.mqtt.controlpacket.IConnectionRequest

/**
 * A broker's session identity: the key persistence stores its session state under, and the CONNECT
 * request that opens the session. How the broker is reached (host, transport, TLS) is not part of
 * the session, so it is supplied to the client separately.
 */
data class MqttBroker(
    val identifier: Int,
    val connectionRequest: IConnectionRequest,
) {
    val brokerId = identifier
    val protocolVersion = connectionRequest.protocolVersion.toByte()
}
