package com.ditchoom.mqtt.client

import com.ditchoom.buffer.SuspendCloseable
import com.ditchoom.data.Writer
import com.ditchoom.mqtt.MqttException
import com.ditchoom.mqtt.connection.MqttConnectionOptions
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.IConnectionAcknowledgment
import com.ditchoom.mqtt.controlpacket.IConnectionRequest
import com.ditchoom.mqtt.controlpacket.IDisconnectNotification
import com.ditchoom.mqtt.controlpacket.format.ReasonCode
import com.ditchoom.socket.ClientSocket
import com.ditchoom.socket.connect
import com.ditchoom.websocket.WebSocketClient
import com.ditchoom.websocket.WebSocketConnectionOptions
import com.ditchoom.websocket.allocate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class MqttSocketSession private constructor(
    private val brokerId: Int,
    val connectionAcknowledgement: IConnectionAcknowledgment,
    private val writeTimeout: Duration,
    private val writer: Writer,
    private val reader: BufferedControlPacketReader,
    private val socketController: SuspendCloseable,
    private val messageSentListener: MutableSharedFlow<ControlPacket>?
) : SuspendCloseable {
    var observer: Observer? = null
        set(value) {
            reader.observer = value
            field = value
        }

    private var isClosed = false

    val incomingPacketFlow = reader.incomingControlPackets

    fun isOpen() = !isClosed && reader.isOpen()

    suspend fun write(packet: ControlPacket) = write(listOf(packet))
    suspend fun write(controlPackets: Collection<ControlPacket>) {
        val b = controlPackets.toBuffer()
        b.resetForWrite()
        writer.write(b, writeTimeout)
        observer?.wrotePackets(brokerId, controlPackets)
        if (controlPackets.filterIsInstance<IDisconnectNotification>().firstOrNull() != null) {
            close()
        }
        if (messageSentListener != null) {
            controlPackets.forEach { messageSentListener.emit(it) }
        }
    }

    internal suspend fun read() = reader.readControlPacket()

    override suspend fun close() {
        isClosed = true
        try {
            withTimeoutOrNull(1.seconds) {
                socketController.close()
            }
        } catch (e: Exception) {
            // ignore close exceptions
        }
    }

    companion object {
        suspend fun open(
            brokerId: Int,
            connectionRequest: IConnectionRequest,
            connectionOps: MqttConnectionOptions,
            observer: Observer? = null,
            messageSentListener: MutableSharedFlow<ControlPacket>? = null,
        ): MqttSocketSession {
            val socket = withContext(Dispatchers.Default) {
                when (connectionOps) {
                    is MqttConnectionOptions.SocketConnection -> {
                        try {
                            ClientSocket.connect(
                                connectionOps.port,
                                connectionOps.host,
                                connectionOps.tls,
                                connectionOps.connectionTimeout
                            )
                        } catch (e: Exception) {
                            e.printStackTrace()
                            throw e
                        }
                    }

                    is MqttConnectionOptions.WebSocketConnectionOptions -> {
                        val wsSocketConnectionOptions = WebSocketConnectionOptions(
                            connectionOps.host,
                            connectionOps.port,
                            connectionOps.websocketEndpoint,
                            connectionOps.protocols,
                            connectionOps.connectionTimeout,
                            connectionOps.readTimeout,
                            connectionOps.writeTimeout,
                            connectionOps.tls,
                            connectionOps.bufferFactory
                        )
                        val client = WebSocketClient.allocate(wsSocketConnectionOptions)
                        try {
                            client.connect()
                        } catch (e: Exception) {
                            client.close()
                            throw e
                        }
                        client
                    }
                }
            }
            val connect = connectionRequest.toBuffer()
            connect.resetForWrite()
            socket.write(connect, connectionOps.writeTimeout)
            messageSentListener?.emit(connectionRequest)
            val bufferedControlPacketReader =
                BufferedControlPacketReader(
                    brokerId,
                    connectionRequest.controlPacketFactory,
                    connectionOps.readTimeout,
                    socket,
                    observer
                )
            val response = bufferedControlPacketReader.readControlPacket()
            if (response is IConnectionAcknowledgment && response.isSuccessful) {
                val s = MqttSocketSession(
                    brokerId,
                    response,
                    connectionOps.writeTimeout,
                    socket,
                    bufferedControlPacketReader,
                    socket,
                    messageSentListener
                )
                s.observer = observer
                return s
            }
            throw MqttException(
                "Invalid response received. Expected successful ConnectionAcknowledgment, instead received $response",
                ReasonCode.MALFORMED_PACKET.byte
            )
        }
    }
}
