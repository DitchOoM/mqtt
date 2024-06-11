package com.ditchoom.mqtt.client

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.SuspendCloseable
import com.ditchoom.data.Reader
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
import com.ditchoom.websocket.ConnectionState
import com.ditchoom.websocket.WebSocketClient
import com.ditchoom.websocket.WebSocketConnectionOptions
import com.ditchoom.websocket.WebSocketMessage
import com.ditchoom.websocket.allocate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
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
    var allocateSharedMemory: Boolean = false,
    var sentMessage: (PlatformBuffer) -> Unit
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
        val b =
            controlPackets.toBuffer(if (allocateSharedMemory) AllocationZone.SharedMemory else AllocationZone.Direct)
        b.resetForWrite()
        writer.write(b, writeTimeout)
        sentMessage(b)
        observer?.wrotePackets(brokerId, connectionAcknowledgement.mqttVersion, controlPackets)
        if (controlPackets.filterIsInstance<IDisconnectNotification>().firstOrNull() != null) {
            close()
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
        sentMessage = {}
    }

    companion object {
        suspend fun open(
            brokerId: Int,
            connectionRequest: IConnectionRequest,
            connectionOps: MqttConnectionOptions,
            allocateSharedMemory: Boolean = false,
            observer: Observer? = null,
            sentMessage: (ReadBuffer) -> Unit = {},
            incomingMessage: (UByte, Int, ReadBuffer) -> Unit = { _, _, _ -> },
        ): MqttSocketSession {
            val zone = if (allocateSharedMemory) {
                AllocationZone.SharedMemory
            } else {
                AllocationZone.Direct
            }
            val connect = connectionRequest.toBuffer(zone)
            connect.resetForWrite()
            val reader: Reader
            val writer: Writer
            val socket = withContext(Dispatchers.Default) {
                when (connectionOps) {
                    is MqttConnectionOptions.SocketConnection -> {
                        try {
                            val s = ClientSocket.connect(
                                connectionOps.port,
                                connectionOps.host,
                                connectionOps.tls,
                                connectionOps.connectionTimeout,
                                zone
                            )
                            reader = object : Reader {
                                override fun isOpen() = s.isOpen()
                                override suspend fun read(timeout: Duration) = s.read(timeout)
                            }
                            writer = object : Writer {
                                override suspend fun write(
                                    buffer: ReadBuffer,
                                    timeout: Duration
                                ): Int =  s.write(buffer, timeout)
                            }
                            s.write(connect, connectionOps.writeTimeout)
                            s
                        } catch (e: Exception) {
                            throw e
                        }
                    }

                    is MqttConnectionOptions.WebSocketConnectionOptions -> {
                        val wsSocketConnectionOptions = WebSocketConnectionOptions(
                            connectionOps.host,
                            connectionOps.port,
                            connectionOps.tls,
                            connectionOps.connectionTimeout,
                            connectionOps.readTimeout,
                            connectionOps.writeTimeout,
                            connectionOps.websocketEndpoint,
                            connectionOps.protocols
                        )
                        val client = WebSocketClient.allocate(
                            wsSocketConnectionOptions,
                            zone
                        )
                        try {
                            reader = object : Reader {
                                override fun isOpen(): Boolean =
                                    client.connectionState.value == ConnectionState.Connected

                                override suspend fun read(timeout: Duration): ReadBuffer =
                                    client
                                        .incomingMessages
                                        .filterIsInstance<WebSocketMessage.Binary>()
                                        .take(1)
                                        .first()
                                        .value
                            }
                            writer = object : Writer {
                                override suspend fun write(
                                    buffer: ReadBuffer,
                                    timeout: Duration
                                ): Int {
                                    val remaining = buffer.remaining()
                                    client.write(buffer)
                                    return remaining
                                }
                            }
                            client.connect()
                            client.write(connect)
                        } catch (e: Throwable) {
                            client.close()
                            throw e
                        }
                        client
                    }
                }
            }
            sentMessage(connect)

            val bufferedControlPacketReader =
                BufferedControlPacketReader(
                    brokerId,
                    connectionRequest.controlPacketFactory,
                    connectionOps.readTimeout,
                    reader,
                    observer,
                    incomingMessage
                )
            val response = bufferedControlPacketReader.readControlPacket()
            if (response is IConnectionAcknowledgment && response.isSuccessful) {
                val s = MqttSocketSession(
                    brokerId,
                    response,
                    connectionOps.writeTimeout,
                    writer,
                    bufferedControlPacketReader,
                    socket,
                    allocateSharedMemory,
                    sentMessage
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
