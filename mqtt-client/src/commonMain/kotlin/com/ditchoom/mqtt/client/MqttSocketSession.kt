package com.ditchoom.mqtt.client

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.SuspendCloseable
import com.ditchoom.mqtt.MqttException
import com.ditchoom.mqtt.connection.MqttConnectionOptions
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.IConnectionAcknowledgment
import com.ditchoom.mqtt.controlpacket.IConnectionRequest
import com.ditchoom.mqtt.controlpacket.IDisconnectNotification
import com.ditchoom.mqtt.controlpacket.format.ReasonCode
import com.ditchoom.websocket.WebSocketConnectionOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class MqttSocketSession private constructor(
    private val brokerId: Int,
    val connectionAcknowledgement: IConnectionAcknowledgment,
    private val writeTimeout: Duration,
    private val transport: MqttTransport,
    private val reader: BufferedControlPacketReader,
    var allocateSharedMemory: Boolean = false,
    var sentMessage: (PlatformBuffer) -> Unit,
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
        transport.write(b, writeTimeout)
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
                transport.close()
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
            val zone =
                if (allocateSharedMemory) {
                    AllocationZone.SharedMemory
                } else {
                    AllocationZone.Direct
                }
            val connect = connectionRequest.toBuffer(zone)
            connect.resetForWrite()
            val transport =
                withContext(Dispatchers.Default) {
                    when (connectionOps) {
                        is MqttConnectionOptions.SocketConnection -> {
                            val tcp =
                                createTcpTransport(
                                    connectionOps.host,
                                    connectionOps.port,
                                    connectionOps.tls,
                                    connectionOps.connectionTimeout,
                                    connectionOps.readTimeout,
                                )
                            tcp.write(connect, connectionOps.writeTimeout)
                            tcp
                        }

                        is MqttConnectionOptions.WebSocketConnectionOptions -> {
                            val wsOptions =
                                WebSocketConnectionOptions(
                                    connectionOps.host,
                                    connectionOps.port,
                                    connectionOps.tls,
                                    connectionOps.connectionTimeout,
                                    connectionOps.readTimeout,
                                    connectionOps.writeTimeout,
                                    connectionOps.websocketEndpoint,
                                    connectionOps.protocols,
                                )
                            val ws = createWebSocketTransport(wsOptions, connectionOps.readTimeout)
                            ws.write(connect, connectionOps.writeTimeout)
                            ws
                        }
                    }
                }
            sentMessage(connect)

            val bufferedControlPacketReader =
                BufferedControlPacketReader(
                    brokerId,
                    connectionRequest.controlPacketFactory,
                    transport,
                    observer,
                    incomingMessage,
                )
            val response = bufferedControlPacketReader.readControlPacket()
            if (response is IConnectionAcknowledgment && response.isSuccessful) {
                val s =
                    MqttSocketSession(
                        brokerId,
                        response,
                        connectionOps.writeTimeout,
                        transport,
                        bufferedControlPacketReader,
                        allocateSharedMemory,
                        sentMessage,
                    )
                s.observer = observer
                return s
            }
            throw MqttException(
                "Invalid response received. Expected successful ConnectionAcknowledgment, instead received $response",
                ReasonCode.MALFORMED_PACKET.byte,
            )
        }
    }
}
