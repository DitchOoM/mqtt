package com.ditchoom.mqtt.serviceworker

import com.ditchoom.buffer.JsBuffer
import com.ditchoom.mqtt.client.ConnectivityManager
import com.ditchoom.mqtt.client.Observer
import com.ditchoom.mqtt.client.toBuffer
import com.ditchoom.mqtt.connection.MqttBroker
import com.ditchoom.mqtt.connection.MqttConnectionOptions
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.IConnectionRequest
import org.khronos.webgl.Uint8Array
import org.w3c.dom.BroadcastChannel
import org.w3c.dom.MessageEvent
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private const val CANCEL_PING_TIMER = "cancelPingTimer"
private const val CONNECT_ONCE_SOCKET_SESSION_WRITE_EXCEPTION = "connectOnceSocketSessionWriteException"
private const val CONNECT_ONCE_SOCKET_SESSION_WRITE_CHANNEL_RECEIVE_EXCEPTION =
    "connectOnceWriteChannelReceiveException"
private const val DELAY_PING = "delayPing"
private const val INCOMING_PACKET = "incomingPacket"
private const val ON_READER_CLOSED = "onReaderClosed"
private const val OPEN_SOCKET_SESSION = "openSocketSession"
private const val READ_FIRST_BYTE = "readFirstByteFromStream"
private const val RECONNECT_AND_RESET_TIMER = "reconnectAndResetTimer"
private const val RECONNECT_IN = "reconnectIn"
private const val RESET_PING_TIMER = "resetPingTimer"
private const val SENDING_PING = "sendingPing"
private const val SHUTDOWN = "shutdown"
private const val STOP_RECONNECTING = "stopReconnecting"
private const val WROTE_PACKETS = "wrotePackets"

class IpcLoggingObserverServiceWorker(
    private val broadcastChannelByBrokerIdAndProtocol: (Int, Byte) -> BroadcastChannel
) : Observer {
    override fun cancelPingTimer(brokerId: Int, protocolVersion: Byte) {
        val broadcastChannel = broadcastChannelByBrokerIdAndProtocol(brokerId, protocolVersion)
        broadcastChannel.postMessage(arrayOf(CANCEL_PING_TIMER))
    }

    override fun connectOnceSocketSessionWriteException(brokerId: Int, protocolVersion: Byte, e: Exception) {
        val broadcastChannel = broadcastChannelByBrokerIdAndProtocol(brokerId, protocolVersion)
        broadcastChannel.postMessage(arrayOf(CONNECT_ONCE_SOCKET_SESSION_WRITE_EXCEPTION, e))
    }

    override fun connectOnceWriteChannelReceiveException(brokerId: Int, protocolVersion: Byte, e: Exception) {
        val broadcastChannel = broadcastChannelByBrokerIdAndProtocol(brokerId, protocolVersion)
        broadcastChannel.postMessage(arrayOf(CONNECT_ONCE_SOCKET_SESSION_WRITE_CHANNEL_RECEIVE_EXCEPTION, e))
    }

    override fun delayPing(brokerId: Int, protocolVersion: Byte, delayDuration: Duration) {
        val broadcastChannel = broadcastChannelByBrokerIdAndProtocol(brokerId, protocolVersion)
        broadcastChannel.postMessage(arrayOf(DELAY_PING, delayDuration.inWholeMilliseconds.toString()))
    }

    override fun incomingPacket(brokerId: Int, protocolVersion: Byte, packet: ControlPacket) {
        val broadcastChannel = broadcastChannelByBrokerIdAndProtocol(brokerId, protocolVersion)
        val buffer = (packet.serialize() as JsBuffer).buffer
        broadcastChannel.postMessage(
            MqttServiceWorkerMessage.ControlPacketReceived(
                brokerId, protocolVersion.toInt(), packet.controlPacketValue, packet.packetIdentifier, buffer
            )
        )
    }

    override fun onReaderClosed(brokerId: Int, protocolVersion: Byte) {
        val broadcastChannel = broadcastChannelByBrokerIdAndProtocol(brokerId, protocolVersion)
        broadcastChannel.postMessage(arrayOf(ON_READER_CLOSED))
    }

    override fun openSocketSession(
        brokerId: Int,
        protocolVersion: Byte,
        connectionRequest: IConnectionRequest,
        connectionOp: MqttConnectionOptions
    ) {
        console.log("open socket session ipc logging observer")
        val broadcastChannel = broadcastChannelByBrokerIdAndProtocol(brokerId, protocolVersion)
        val c = (connectionOp as MqttConnectionOptions.WebSocketConnectionOptions)
        val uint8Array = (connectionRequest.serialize() as JsBuffer).buffer
        console.log("post OPEN_SOCKET_SESSION")
        broadcastChannel.postMessage(
            arrayOf(
                OPEN_SOCKET_SESSION, uint8Array,
                c.host, c.port, c.tls, c.connectionTimeout.inWholeMilliseconds.toString(),
                c.readTimeout.inWholeMilliseconds.toString(), c.writeTimeout.inWholeMilliseconds.toString(),
                c.websocketEndpoint, c.protocols.joinToString()
            )
        )
    }

    override fun readFirstByteFromStream(brokerId: Int, protocolVersion: Byte) {
        val broadcastChannel = broadcastChannelByBrokerIdAndProtocol(brokerId, protocolVersion)
        broadcastChannel.postMessage(arrayOf(READ_FIRST_BYTE))
    }

    override fun reconnectAndResetTimer(
        brokerId: Int,
        protocolVersion: Byte,
        endReason: ConnectivityManager.ConnectionEndReason
    ) {
        val broadcastChannel = broadcastChannelByBrokerIdAndProtocol(brokerId, protocolVersion)
        broadcastChannel.postMessage(
            arrayOf(
                RECONNECT_AND_RESET_TIMER,
                endReason.shouldContinueReconnecting,
                endReason.shouldResetTimer,
                endReason.msg
            )
        )
    }

    override fun reconnectIn(
        brokerId: Int, protocolVersion: Byte,
        currentDelay: Duration,
        endReason: ConnectivityManager.ConnectionEndReason
    ) {
        val broadcastChannel = broadcastChannelByBrokerIdAndProtocol(brokerId, protocolVersion)
        broadcastChannel.postMessage(
            arrayOf(
                RECONNECT_IN,
                currentDelay.inWholeMilliseconds.toString(),
                endReason.shouldContinueReconnecting,
                endReason.shouldResetTimer,
                endReason.msg
            )
        )
    }

    override fun resetPingTimer(brokerId: Int, protocolVersion: Byte) {
        val broadcastChannel = broadcastChannelByBrokerIdAndProtocol(brokerId, protocolVersion)
        broadcastChannel.postMessage(arrayOf(RESET_PING_TIMER))
    }

    override fun sendingPing(brokerId: Int, protocolVersion: Byte) {
        val broadcastChannel = broadcastChannelByBrokerIdAndProtocol(brokerId, protocolVersion)
        broadcastChannel.postMessage(arrayOf(SENDING_PING))
    }

    override fun shutdown(brokerId: Int, protocolVersion: Byte) {
        val broadcastChannel = broadcastChannelByBrokerIdAndProtocol(brokerId, protocolVersion)
        broadcastChannel.postMessage(arrayOf(SHUTDOWN))
    }

    override fun stopReconnecting(
        brokerId: Int,
        protocolVersion: Byte,
        endReason: ConnectivityManager.ConnectionEndReason
    ) {
        val broadcastChannel = broadcastChannelByBrokerIdAndProtocol(brokerId, protocolVersion)
        broadcastChannel.postMessage(
            arrayOf(
                STOP_RECONNECTING,
                endReason.shouldContinueReconnecting,
                endReason.shouldResetTimer,
                endReason.msg
            )
        )
    }

    override fun wrotePackets(brokerId: Int, protocolVersion: Byte, controlPackets: Collection<ControlPacket>) {
        val broadcastChannel = broadcastChannelByBrokerIdAndProtocol(brokerId, protocolVersion)
        val buffer = controlPackets.toBuffer()
        broadcastChannel.postMessage(arrayOf(WROTE_PACKETS, buffer))
    }

}

class IpcObserverClient(private val broker: MqttBroker) {
    private val factory = broker.connectionRequest.controlPacketFactory
    private val protocolVersion = factory.protocolVersion.toByte()
    fun forwardMessageEvent(event: MessageEvent, observer: Observer) {
        val array = event.data as? Array<*>
        if (array != null && array.isNotEmpty()) {
            console.log("forward ipc event", event)
            val firstValue = array.first() as String
            when (firstValue) {
                CANCEL_PING_TIMER -> {
                    observer.cancelPingTimer(broker.identifier, protocolVersion)
                }

                CONNECT_ONCE_SOCKET_SESSION_WRITE_EXCEPTION -> {
                    observer.connectOnceSocketSessionWriteException(
                        broker.identifier, protocolVersion,
                        array[1].unsafeCast<Exception>()
                    )
                }

                CONNECT_ONCE_SOCKET_SESSION_WRITE_CHANNEL_RECEIVE_EXCEPTION -> {
                    observer.connectOnceWriteChannelReceiveException(
                        broker.identifier, protocolVersion,
                        array[1].unsafeCast<Exception>()
                    )
                }

                DELAY_PING -> {
                    observer.delayPing(broker.identifier, protocolVersion, array[1].toString().toLong().milliseconds)
                }

                INCOMING_PACKET -> {
                    console.log("reading incoming packet")
                    val uint8Array = array[1] as Uint8Array
                    val buffer = JsBuffer(uint8Array, position = 0, limit = uint8Array.length)
                    observer.incomingPacket(broker.identifier, protocolVersion, factory.from(buffer))
                    console.log("read incoming packet")
                }

                ON_READER_CLOSED -> {
                    observer.onReaderClosed(broker.identifier, protocolVersion)
                }

                OPEN_SOCKET_SESSION -> {
                    console.log("open socket session OPEN_SOCKET_SESSION")
                    val uint8Array = array[1] as Uint8Array
                    val buffer = JsBuffer(uint8Array, position = 0, limit = uint8Array.length)
                    val connectionRequest = factory.from(buffer) as IConnectionRequest
                    val wsConnectionOptions = MqttConnectionOptions.WebSocketConnectionOptions(
                        array[2].toString(),
                        array[3] as Int,
                        array[4] as Boolean,
                        array[5].toString().toLong().milliseconds,
                        array[6].toString().toLong().milliseconds,
                        array[7].toString().toLong().milliseconds,
                        array[8].toString(),
                        array[8].toString().split(", ")
                    )
                    observer.openSocketSession(
                        broker.identifier,
                        protocolVersion,
                        connectionRequest,
                        wsConnectionOptions
                    )

                    console.log("open socket session done")
                }

                READ_FIRST_BYTE -> {
                    observer.readFirstByteFromStream(broker.identifier, protocolVersion)
                }

                RECONNECT_AND_RESET_TIMER -> {
                    observer.reconnectAndResetTimer(
                        broker.identifier, protocolVersion,
                        ConnectivityManager.ConnectionEndReason(
                            array[1] as Boolean, array[2] as Boolean,
                            array[3]?.toString()
                        )
                    )
                }

                RECONNECT_IN -> {
                    observer.reconnectIn(
                        broker.identifier, protocolVersion, array[1].toString().toLong().milliseconds,
                        ConnectivityManager.ConnectionEndReason(
                            array[2] as Boolean, array[3] as Boolean,
                            array[4]?.toString()
                        )
                    )
                }

                RESET_PING_TIMER -> {
                    observer.resetPingTimer(broker.identifier, protocolVersion)
                }

                SENDING_PING -> {
                    observer.sendingPing(broker.identifier, protocolVersion)
                }

                SHUTDOWN -> {
                    observer.shutdown(broker.identifier, protocolVersion)
                }

                STOP_RECONNECTING -> {
                    observer.stopReconnecting(
                        broker.identifier, protocolVersion, ConnectivityManager.ConnectionEndReason(
                            array[1] as Boolean, array[2] as Boolean,
                            array[3]?.toString()
                        )
                    )
                }

                WROTE_PACKETS -> {
                    val uint8Array = array[1] as Uint8Array
                    val buffer = JsBuffer(uint8Array, position = 0, limit = uint8Array.length)
                    val packets = mutableListOf<ControlPacket>()
                    while (buffer.hasRemaining()) {
                        packets += factory.from(buffer)
                    }
                    observer.wrotePackets(broker.identifier, protocolVersion, packets)
                }
            }

        }
    }

}

