package com.ditchoom.mqtt.client

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.ReadWriteBuffer
import com.ditchoom.buffer.pool.TieredBufferPool
import com.ditchoom.buffer.withPooling
import com.ditchoom.mqtt.MqttException
import com.ditchoom.mqtt.connection.MqttConnectionOptions
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.IConnectionAcknowledgment
import com.ditchoom.mqtt.controlpacket.IConnectionRequest
import com.ditchoom.mqtt.controlpacket.IDisconnectNotification
import com.ditchoom.mqtt.controlpacket.IPublishMessage
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
    val factory: BufferFactory = BufferFactory.Default,
    var sentMessage: (PlatformBuffer) -> Unit,
) {
    private val writePool = TieredBufferPool(factory = factory)
    private val pooledFactory = factory.withPooling(writePool)

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
        // Fast path: single PUBLISH with payload → scatter-gather (avoids payload copy)
        if (controlPackets.size == 1) {
            val packet = controlPackets.first()
            if (packet is IPublishMessage) {
                val payload = packet.payload
                if (payload != null && payload.remaining() > 0) {
                    writePublishZeroCopy(packet, controlPackets)
                    return
                }
            }
        }
        val b = controlPackets.toBuffer(pooledFactory)
        b.resetForRead()
        transport.write(b, writeTimeout)
        sentMessage(b)
        b.freeNativeMemory()
        observer?.wrotePackets(brokerId, connectionAcknowledgement.mqttVersion, controlPackets)
        if (controlPackets.filterIsInstance<IDisconnectNotification>().firstOrNull() != null) {
            close()
        }
    }

    private suspend fun writePublishZeroCopy(
        packet: IPublishMessage,
        controlPackets: Collection<ControlPacket>,
    ) {
        val payload = packet.payload!!
        // Acquire small pooled buffer for the header (topic + packet ID + fixed header).
        // 512 bytes from the small pool is more than enough.
        val headerBuf = writePool.acquire(64)
        try {
            val header = packet.serializeHeaderToSlice(headerBuf, payload.remaining())
            transport.writeGathered(listOf(header, payload), writeTimeout)
        } finally {
            writePool.release(headerBuf)
        }
        observer?.wrotePackets(brokerId, connectionAcknowledgement.mqttVersion, controlPackets)
    }

    internal suspend fun read() = reader.readControlPacket()

    suspend fun close() {
        isClosed = true
        try {
            withTimeoutOrNull(1.seconds) {
                transport.close()
            }
        } catch (e: Exception) {
            // ignore close exceptions
        }
        writePool.clear()
        sentMessage = {}
    }

    companion object {
        suspend fun open(
            brokerId: Int,
            connectionRequest: IConnectionRequest,
            connectionOps: MqttConnectionOptions,
            factory: BufferFactory = BufferFactory.Default,
            observer: Observer? = null,
            sentMessage: (ReadBuffer) -> Unit = {},
            incomingMessage: (UByte, Int, ReadBuffer) -> Unit = { _, _, _ -> },
        ): MqttSocketSession {
            val connect = connectionRequest.toBuffer(factory)
            connect.resetForRead()
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
                            val ws = createWebSocketTransport(wsOptions, connectionOps.readTimeout, factory)
                            ws.write(connect, connectionOps.writeTimeout)
                            ws
                        }
                    }
                }
            sentMessage(connect)
            connect.freeNativeMemory()

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
                        factory,
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
