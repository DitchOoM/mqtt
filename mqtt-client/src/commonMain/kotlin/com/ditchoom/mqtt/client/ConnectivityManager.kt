package com.ditchoom.mqtt.client

import com.ditchoom.buffer.flow.Connection
import com.ditchoom.mqtt.Persistence
import com.ditchoom.mqtt.connection.MqttBroker
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.IConnectionAcknowledgment
import com.ditchoom.mqtt.controlpacket.IDisconnectNotification
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

/**
 * Manages a single MQTT connection: handshake, read/write loops, ping timer, session recovery.
 *
 * Does NOT handle reconnection — the caller provides a [Connection] that may already be wrapped
 * with reconnection logic (e.g. socket's `ReconnectingConnection`). This keeps mqtt-client
 * transport-agnostic with no hard dependency on the socket library.
 */
class ConnectivityManager(
    internal val persistence: Persistence,
    internal val broker: MqttBroker,
    private val connect: suspend () -> Connection<ControlPacket>,
) {
    var connectionCount = 0L
        private set
    var connectionAttempts = 0L
        private set

    var observer: Observer? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val readChannel = MutableSharedFlow<ControlPacket>(1)
    private val writeChannel = Channel<Collection<ControlPacket>>(Channel.BUFFERED)
    private val connectionBroadcastInternal = MutableSharedFlow<IConnectionAcknowledgment>()
    val connectionBroadcastChannel: SharedFlow<IConnectionAcknowledgment> = connectionBroadcastInternal

    lateinit var processor: ControlPacketProcessor
        private set

    private var currentConnack: IConnectionAcknowledgment? = null

    fun currentConnack(): IConnectionAcknowledgment? = currentConnack

    /**
     * Connects, performs the MQTT handshake, then runs read/write loops until the connection
     * ends or the coroutine is cancelled.
     *
     * If the caller wants reconnection, wrap the [connect] factory with a reconnecting
     * connection (e.g. socket's `ReconnectingConnection`) before passing it in.
     */
    suspend fun run() {
        processor = ControlPacketProcessor(broker, readChannel, writeChannel, persistence)
        processor.observer = observer

        val conn = connectAndHandshake()
        try {
            coroutineScope {
                launch { processor.processIncomingMessages() }
                launch { processor.runPingTimer() }
                launch { writeLoop(conn) }

                conn.receive().collect { packet ->
                    observer?.incomingPacket(
                        broker.identifier,
                        broker.connectionRequest.protocolVersion.toByte(),
                        packet,
                    )
                    readChannel.emit(packet)
                }
            }
        } finally {
            withContext(NonCancellable) {
                _connectionState.value = ConnectionState.Disconnected
                conn.close()
            }
        }
    }

    private suspend fun connectAndHandshake(): Connection<ControlPacket> {
        connectionAttempts++
        val conn = connect()
        try {
            _connectionState.value = ConnectionState.Handshaking
            conn.send(broker.connectionRequest as ControlPacket)
            val response = conn.receive().first()
            if (response is IConnectionAcknowledgment && response.isSuccessful) {
                connectionCount++
                currentConnack = response
                _connectionState.value = ConnectionState.Connected(response)
                prepareSession(response)
                connectionBroadcastInternal.emit(response)
                return conn
            }
            conn.close()
            if (response is IConnectionAcknowledgment) {
                throw MqttConnectionException.ConnackRejected(
                    "CONNACK rejected: ${response.connectionReason}",
                    response.byte1,
                )
            }
            throw MqttConnectionException.ProtocolError(
                "Expected CONNACK, got ${response::class.simpleName}",
            )
        } catch (e: MqttConnectionException) {
            throw e
        } catch (e: CancellationException) {
            conn.close()
            throw e
        } catch (e: Exception) {
            conn.close()
            throw e
        }
    }

    private suspend fun writeLoop(conn: Connection<ControlPacket>) {
        while (currentCoroutineContext().isActive) {
            val packets =
                try {
                    writeChannel.receive()
                } catch (_: Exception) {
                    break
                }
            for (packet in packets) {
                conn.send(packet)
                processor.onPacketSent(packet)
                observer?.wrotePackets(
                    broker.identifier,
                    broker.connectionRequest.mqttVersion,
                    listOf(packet),
                )
            }
            processor.noteActivity()
            if (packets.any { it is IDisconnectNotification }) {
                break
            }
        }
    }

    private suspend fun prepareSession(connack: IConnectionAcknowledgment) {
        val sessionPresent = connack.sessionPresent
        if (broker.connectionRequest.cleanStart && sessionPresent) {
            throw MqttConnectionException.ProtocolError(
                "[MQTT-3.2.2-4] Server reported session present but cleanStart was requested",
            )
        }
        if (sessionPresent) {
            emptyWriteChannel()
            val messages = processor.queueMessagesOnReconnect()
            for (packet in messages) {
                writeChannel.send(listOf(packet))
            }
        } else {
            persistence.clearMessages(broker)
        }
    }

    /**
     * Gracefully shuts down the connection.
     * Call from a NonCancellable context if needed during cancellation cleanup.
     */
    suspend fun shutdown(
        sendDisconnect: Boolean = true,
        drain: Boolean = false,
    ) {
        if (drain) {
            try {
                withTimeout(5.seconds) {
                    while (!persistence.isQueueClear(broker, includeSubscriptions = false)) {
                        delay(10)
                    }
                }
            } catch (_: Exception) {
                // drain timeout — proceed with shutdown
            }
        }
        if (sendDisconnect) {
            sendDisconnect()
        }
        writeChannel.close()
        observer?.shutdown(broker.identifier, broker.connectionRequest.protocolVersion.toByte())
    }

    suspend fun sendDisconnect() {
        val disconnect = broker.connectionRequest.controlPacketFactory.disconnect()
        try {
            writeChannel.send(listOf(disconnect))
        } catch (_: Exception) {
            // channel closed or send failed — ignore
        }
    }

    private fun emptyWriteChannel() {
        while (writeChannel.tryReceive().isSuccess) {
            // drain
        }
    }
}
