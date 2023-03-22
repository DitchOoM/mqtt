package com.ditchoom.mqtt.client

import com.ditchoom.mqtt.connection.MqttConnectionOptions
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.IConnectionRequest
import kotlin.time.Duration

interface Observer {
    fun readFirstByteFromStream(brokerId: Int, protocolVersion: Byte)
    fun incomingPacket(brokerId: Int, protocolVersion: Byte, packet: ControlPacket)

    fun wrotePackets(brokerId: Int, protocolVersion: Byte, controlPackets: Collection<ControlPacket>)

    fun openSocketSession(
        brokerId: Int,
        protocolVersion: Byte,
        connectionRequest: IConnectionRequest,
        connectionOp: MqttConnectionOptions
    )

    fun shutdown(brokerId: Int, protocolVersion: Byte)
    fun connectOnceWriteChannelReceiveException(brokerId: Int, protocolVersion: Byte, e: Exception)
    fun connectOnceSocketSessionWriteException(brokerId: Int, protocolVersion: Byte, e: Exception)
    fun onReaderClosed(brokerId: Int, protocolVersion: Byte)
    fun resetPingTimer(brokerId: Int, protocolVersion: Byte)
    fun sendingPing(brokerId: Int, protocolVersion: Byte)
    fun delayPing(brokerId: Int, protocolVersion: Byte, delayDuration: Duration)
    fun cancelPingTimer(brokerId: Int, protocolVersion: Byte)
    fun stopReconnecting(brokerId: Int, protocolVersion: Byte, endReason: ConnectivityManager.ConnectionEndReason)
    fun reconnectAndResetTimer(brokerId: Int, protocolVersion: Byte, endReason: ConnectivityManager.ConnectionEndReason)
    fun reconnectIn(
        brokerId: Int,
        protocolVersion: Byte,
        currentDelay: Duration,
        endReason: ConnectivityManager.ConnectionEndReason
    )
}
