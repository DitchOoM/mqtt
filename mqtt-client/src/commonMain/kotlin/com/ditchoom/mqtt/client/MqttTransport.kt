package com.ditchoom.mqtt.client

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.AutoFillingSuspendingStreamProcessor
import com.ditchoom.buffer.stream.EndOfStreamException
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.stream.builder
import com.ditchoom.socket.ConnectionOptions
import com.ditchoom.socket.SocketConnection
import com.ditchoom.socket.SocketOptions
import com.ditchoom.socket.TlsConfig
import com.ditchoom.websocket.ConnectionState
import com.ditchoom.websocket.WebSocketClient
import com.ditchoom.websocket.WebSocketConnectionOptions
import com.ditchoom.websocket.allocate
import kotlinx.coroutines.flow.first
import kotlin.time.Duration

interface MqttTransport {
    fun isOpen(): Boolean

    suspend fun write(
        buffer: ReadBuffer,
        timeout: Duration,
    ): Int

    /**
     * Writes multiple buffers as a single logical message.
     * TCP: sequential writes (zero-copy, no concatenation).
     * WebSocket: must concatenate into single frame per MQTT spec.
     */
    suspend fun writeGathered(
        buffers: List<ReadBuffer>,
        timeout: Duration,
    ): Int

    val stream: AutoFillingSuspendingStreamProcessor

    suspend fun close()
}

class TcpMqttTransport(
    private val connection: SocketConnection,
    override val stream: AutoFillingSuspendingStreamProcessor,
) : MqttTransport {
    override fun isOpen(): Boolean = connection.isOpen

    override suspend fun write(
        buffer: ReadBuffer,
        timeout: Duration,
    ): Int = connection.write(buffer, timeout)

    override suspend fun writeGathered(
        buffers: List<ReadBuffer>,
        timeout: Duration,
    ): Int = connection.writeGathered(buffers, timeout)

    override suspend fun close() = connection.close()
}

class WebSocketMqttTransport(
    private val client: WebSocketClient,
    override val stream: AutoFillingSuspendingStreamProcessor,
    private val pool: BufferPool,
) : MqttTransport {
    override fun isOpen(): Boolean = client.connectionState.value == ConnectionState.Connected

    override suspend fun write(
        buffer: ReadBuffer,
        timeout: Duration,
    ): Int {
        val remaining = buffer.remaining()
        client.write(buffer)
        return remaining
    }

    override suspend fun writeGathered(
        buffers: List<ReadBuffer>,
        timeout: Duration,
    ): Int {
        // WebSocket: each MQTT packet must be a single WebSocket frame.
        // Concatenate buffers into one before sending.
        if (buffers.size == 1) return write(buffers[0], timeout)
        val totalSize = buffers.sumOf { it.remaining() }
        val combined = PlatformBuffer.allocate(totalSize)
        for (buf in buffers) combined.write(buf)
        combined.resetForRead()
        val result = write(combined, timeout)
        combined.freeNativeMemory()
        return result
    }

    override suspend fun close() {
        client.close()
        pool.clear()
    }
}

internal suspend fun createTcpTransport(
    host: String,
    port: Int,
    tls: Boolean,
    connectionTimeout: Duration,
    readTimeout: Duration,
): TcpMqttTransport {
    val socketOptions =
        if (tls) {
            SocketOptions(tls = TlsConfig())
        } else {
            SocketOptions()
        }
    val connection =
        SocketConnection.connect(
            host,
            port,
            ConnectionOptions(
                socketOptions = socketOptions,
                connectionTimeout = connectionTimeout,
                readTimeout = readTimeout,
            ),
        )
    val stream =
        AutoFillingSuspendingStreamProcessor(connection.stream) {
            val bytesRead = connection.readIntoStream(readTimeout)
            if (bytesRead <= 0) throw EndOfStreamException()
        }
    return TcpMqttTransport(connection, stream)
}

internal suspend fun createWebSocketTransport(
    connectionOptions: WebSocketConnectionOptions,
    readTimeout: Duration,
    factory: BufferFactory = BufferFactory.Default,
): WebSocketMqttTransport {
    val pool = BufferPool()
    val client = WebSocketClient.allocate(connectionOptions, bufferFactory = factory, bufferPool = pool)
    try {
        client.connect()
        val state = client.connectionState.value
        if (state is ConnectionState.Disconnected) {
            throw state.t ?: IllegalStateException("WebSocket connection failed")
        }
        if (state != ConnectionState.Connected) {
            throw IllegalStateException("WebSocket connection not established, state: $state")
        }
    } catch (e: Throwable) {
        pool.clear()
        client.close()
        throw e
    }
    val stream =
        StreamProcessor.builder(pool).buildSuspendingWithAutoFill { autoFiller ->
            val buffer = client.incomingBinaryMessages.first()
            // Don't call resetForRead() — the WebSocket client already delivers
            // payload buffers in read mode (position=0, limit=payloadSize).
            // Calling resetForRead() would set limit=0, discarding the data.
            if (buffer.remaining() > 0) {
                autoFiller.append(buffer)
            } else {
                throw EndOfStreamException()
            }
        }
    return WebSocketMqttTransport(client, stream, pool)
}
