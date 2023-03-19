package com.ditchoom.common

import com.ditchoom.mqtt.client.ConnectivityManager
import com.ditchoom.mqtt.client.Observer
import com.ditchoom.mqtt.connection.MqttConnectionOptions
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.IConnectionRequest
import kotlin.time.Duration

class LoggingObserver(private val logCallback: (Int?, String) -> Unit) : Observer {

    private fun buildItem(text: String, brokerId: Int? = null) {
        logCallback(brokerId, text)
    }

    override fun cancelPingTimer(brokerId: Int) {
//        buildItem("cancel ping timer")
    }

    override fun connectOnceSocketSessionWriteException(brokerId: Int, e: Exception) {
        buildItem("connect once socket session write exception $e", brokerId)
    }

    override fun connectOnceWriteChannelReceiveException(brokerId: Int, e: Exception) {
        buildItem("connect once write channel receive exception $e", brokerId)
    }

    override fun delayPing(brokerId: Int, delayDuration: Duration) {
        buildItem("delay ping $delayDuration", brokerId)
    }

    override fun incomingPacket(brokerId: Int, packet: ControlPacket) {
        buildItem("IN:  $packet", brokerId)
    }

    override fun onReaderClosed(brokerId: Int) {
        buildItem("reader closed", brokerId)
    }

    override fun openSocketSession(connectionRequest: IConnectionRequest, connectionOp: MqttConnectionOptions) {
        buildItem("OPEN: $connectionOp $connectionRequest")
    }

    override fun readFirstByteFromStream(brokerId: Int) {
//        buildItem("read byte1")
    }

    override fun resetPingTimer(brokerId: Int) {
        buildItem("reset ping timer", brokerId)
    }

    override fun sendingPing(brokerId: Int) {
        buildItem("sending ping", brokerId)
    }

    override fun shutdown() {
        buildItem("shutdown")
    }

    override fun wrotePackets(brokerId: Int, controlPackets: Collection<ControlPacket>) {
        buildItem("OUT: ${controlPackets.joinToString()}", brokerId)
    }

    override fun reconnectAndResetTimer(brokerId: Int, endReason: ConnectivityManager.ConnectionEndReason) {
        buildItem("Reconnect and reset timer $endReason", brokerId)
    }

    override fun reconnectIn(brokerId: Int, currentDelay: Duration, endReason: ConnectivityManager.ConnectionEndReason) {
        buildItem("Reconnect in $currentDelay $endReason", brokerId)
    }

    override fun stopReconnecting(brokerId: Int, endReason: ConnectivityManager.ConnectionEndReason) {
        buildItem("Stop Reconnecting $endReason", brokerId)
    }
}