package com.ditchoom.mqtt.client

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
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
        val b = controlPackets.toBuffer(factory)
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
        // Allocate a small buffer for the fixed header + variable header (topic + packet ID).
        // The payload is written separately via scatter-gather to avoid copying.
        val headerBuf = BufferFactory.Default.allocate(64)
        try {
            val headerSlice = packet.serializeHeaderToSlice(headerBuf, payload.remaining())
            // serializeHeaderToSlice returns a slice (ReadBuffer). The socket write requires
            // PlatformBuffer, so copy the small header (~20 bytes) into a fresh buffer.
            val header = BufferFactory.Default.allocate(headerSlice.remaining())
            header.write(headerSlice)
            header.resetForRead()
            transport.writeGathered(listOf(header, payload), writeTimeout)
            header.freeNativeMemory()
        } finally {
            headerBuf.freeNativeMemory()
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
