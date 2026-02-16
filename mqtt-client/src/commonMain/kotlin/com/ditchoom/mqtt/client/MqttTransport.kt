package com.ditchoom.mqtt.client

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.SuspendCloseable
import com.ditchoom.buffer.pool.BufferPool
import com.ditchoom.buffer.stream.StreamProcessor
import com.ditchoom.buffer.stream.SuspendingStreamProcessor
import com.ditchoom.buffer.stream.builder
import com.ditchoom.socket.ConnectionOptions
import com.ditchoom.socket.SocketConnection
import com.ditchoom.socket.SocketOptions
import com.ditchoom.socket.TlsConfig
import com.ditchoom.websocket.ConnectionState
import com.ditchoom.websocket.WebSocketClient
import com.ditchoom.websocket.WebSocketConnectionOptions
import com.ditchoom.websocket.WebSocketMessage
import com.ditchoom.websocket.allocate
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlin.time.Duration

sealed interface MqttTransport : SuspendCloseable {
    fun isOpen(): Boolean

    suspend fun write(
        buffer: ReadBuffer,
        timeout: Duration,
    ): Int

    val stream: SuspendingStreamProcessor

    suspend fun readIntoStream(timeout: Duration): Int
}

class TcpMqttTransport(
    private val connection: SocketConnection,
    private val readTimeout: Duration,
) : MqttTransport {
    override fun isOpen(): Boolean = connection.isOpen

    override suspend fun write(
        buffer: ReadBuffer,
        timeout: Duration,
    ): Int = connection.write(buffer, timeout)

    override val stream: SuspendingStreamProcessor get() = connection.stream

    override suspend fun readIntoStream(timeout: Duration): Int = connection.readIntoStream(timeout)

    override suspend fun close() = connection.close()
}

class WebSocketMqttTransport(
    private val client: WebSocketClient,
    override val stream: SuspendingStreamProcessor,
    private val readTimeout: Duration,
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

    override suspend fun readIntoStream(timeout: Duration): Int {
        val message =
            client
                .incomingMessages
                .filterIsInstance<WebSocketMessage.Binary>()
                .take(1)
                .first()
        val buffer = message.value
        buffer.resetForRead()
        val bytesRead = buffer.remaining()
        if (bytesRead > 0) stream.append(buffer)
        return bytesRead
    }

    override suspend fun close() = client.close()
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
    return TcpMqttTransport(connection, readTimeout)
}

internal suspend fun createWebSocketTransport(
    connectionOptions: WebSocketConnectionOptions,
    readTimeout: Duration,
): WebSocketMqttTransport {
    val client = WebSocketClient.allocate(connectionOptions)
    try {
        client.connect()
    } catch (e: Throwable) {
        client.close()
        throw e
    }
    val pool = BufferPool()
    val stream = StreamProcessor.builder(pool).buildSuspending()
    return WebSocketMqttTransport(client, stream, readTimeout)
}
