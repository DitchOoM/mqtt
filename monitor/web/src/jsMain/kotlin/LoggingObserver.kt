import com.ditchoom.mqtt.client.ConnectivityManager
import com.ditchoom.mqtt.client.Observer
import com.ditchoom.mqtt.connection.MqttConnectionOptions
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.IConnectionRequest
import kotlin.time.Duration

class LoggingObserver(private val logCallback: (String) -> Unit) : Observer {

    private fun buildItem(text: String) {
        logCallback(text)
    }

    override fun cancelPingTimer(brokerId: Int) {
//        buildItem("cancel ping timer")
    }

    override fun connectOnceSocketSessionWriteException(brokerId: Int, e: Exception) {
        buildItem("connect once socket session write exception $e")
    }

    override fun connectOnceWriteChannelReceiveException(brokerId: Int, e: Exception) {
        buildItem("connect once write channel receive exception $e")
    }

    override fun delayPing(brokerId: Int, delayDuration: Duration) {
        buildItem("delay ping $delayDuration")
    }

    override fun incomingPacket(brokerId: Int, packet: ControlPacket) {
        buildItem("IN:  $packet")
    }

    override fun onReaderClosed(brokerId: Int) {
        buildItem("reader closed")
    }

    override fun openSocketSession(connectionRequest: IConnectionRequest, connectionOp: MqttConnectionOptions) {
        buildItem("OPEN: $connectionOp $connectionRequest")
    }

    override fun readFirstByteFromStream(brokerId: Int) {
//        buildItem("read byte1")
    }

    override fun resetPingTimer(brokerId: Int) {
        buildItem("reset ping timer")
    }

    override fun sendingPing(brokerId: Int) {
        buildItem("sending ping")
    }

    override fun shutdown() {
        buildItem("shutdown")
    }

    override fun wrotePackets(brokerId: Int, controlPackets: Collection<ControlPacket>) {
        buildItem("OUT: ${controlPackets.joinToString()}")
    }

    override fun reconnectAndResetTimer(brokerId: Int, endReason: ConnectivityManager.ConnectionEndReason) {
        buildItem("Reconnect and reset timer $endReason")
    }

    override fun reconnectIn(brokerId: Int, currentDelay: Duration, endReason: ConnectivityManager.ConnectionEndReason) {
        buildItem("Reconnect in $currentDelay $endReason")
    }

    override fun stopReconnecting(brokerId: Int, endReason: ConnectivityManager.ConnectionEndReason) {
        buildItem("Stop Reconnecting $endReason")
    }
}