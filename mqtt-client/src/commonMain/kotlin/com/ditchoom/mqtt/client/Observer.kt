package com.ditchoom.mqtt.client

import com.ditchoom.mqtt.connection.MqttConnectionOptions
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.IConnectionRequest
import kotlin.time.Duration

interface Observer {
    fun readFirstByteFromStream(brokerId: Int)
    fun incomingPacket(brokerId: Int, packet: ControlPacket)

    fun wrotePackets(brokerId: Int, controlPackets: Collection<ControlPacket>)

    fun openSocketSession(connectionRequest: IConnectionRequest, connectionOp: MqttConnectionOptions)
    fun shutdown()
    fun connectOnceWriteChannelReceiveException(brokerId: Int, e: Exception)
    fun connectOnceSocketSessionWriteException(brokerId: Int, e: Exception)
    fun onReaderClosed(brokerId: Int)
    fun resetPingTimer(brokerId: Int)
    fun sendingPing(brokerId: Int)
    fun delayPing(brokerId: Int, delayDuration: Duration)
    fun cancelPingTimer(brokerId: Int)
    fun stopReconnecting(brokerId: Int, endReason: ConnectivityManager.ConnectionEndReason)
    fun reconnectAndResetTimer(brokerId: Int, endReason: ConnectivityManager.ConnectionEndReason)
    fun reconnectIn(brokerId: Int, currentDelay: Duration, endReason: ConnectivityManager.ConnectionEndReason)
}
