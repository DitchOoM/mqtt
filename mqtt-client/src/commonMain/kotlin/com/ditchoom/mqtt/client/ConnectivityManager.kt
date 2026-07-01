package com.ditchoom.mqtt.client

import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.Payload
import com.ditchoom.buffer.flow.Connection
import com.ditchoom.mqtt.Persistence
import com.ditchoom.mqtt.connection.MqttBroker
import com.ditchoom.mqtt.connection.MqttConnectionOptions
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.IConnectionAcknowledgment
import com.ditchoom.mqtt.controlpacket.IDisconnectNotification
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

/**
 * Owns a single MQTT broker connection: iterates [MqttBroker.connectionOps] for failover,
 * performs the CONNECT/CONNACK handshake, runs read/write loops, and surfaces session state.
 *
 * The caller — [MqttClient] — is responsible for re-invoking [run] when the underlying
 * connection ends (session-resume / "stay connected" pattern). That outer loop keeps the
 * reconnection policy in one place; [run] itself is a single-session body.
 *
 * [connectSingle] is atomic: given a [MqttConnectionOptions] and the per-topic codec lookup,
 * establish one transport. Option iteration happens here so each attempted option is counted
 * in [connectionAttempts]. The lookup is invoked by the wire-decoder for every incoming
 * PUBLISH, so threading it through the call (rather than capturing at construction) means
 * a caller-supplied [connectSingle] cannot bypass the per-client [TopicCodecRegistry].
 */
class ConnectivityManager(
    internal val persistence: Persistence,
    internal val broker: MqttBroker,
    private val publishCodecForTopic: (topicName: String) -> Codec<out Payload>?,
    private val connectSingle: suspend (MqttConnectionOptions, (topicName: String) -> Codec<out Payload>?) -> Connection<ControlPacket>,
) {
    var connectionCount = 0L
        private set
    var connectionAttempts = 0L
        private set

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val readChannel = MutableSharedFlow<ControlPacket>(1)
    private val writeChannel = Channel<Collection<ControlPacket>>(Channel.BUFFERED)
    private val connectionBroadcastInternal = MutableSharedFlow<IConnectionAcknowledgment>()
    val connectionBroadcastChannel: SharedFlow<IConnectionAcknowledgment> = connectionBroadcastInternal

    /**
     * Completes after the first full pass of [connectAndHandshake] (success OR all options
     * exhausted). Lets [MqttClient.start] suspend until observable counters are accurate
     * — tests asserting `connectionAttempts == 2` immediately after `start()` relied on this
     * invariant in v1 and would race against the launched coroutine without it.
     */
    internal val firstAttemptComplete = CompletableDeferred<Unit>()

    val processor: ControlPacketProcessor =
        ControlPacketProcessor(broker, readChannel, writeChannel, persistence)

    private var currentConnack: IConnectionAcknowledgment? = null

    fun currentConnack(): IConnectionAcknowledgment? = currentConnack

    /**
     * Connects, performs the MQTT handshake, then runs read/write loops until the connection
     * ends or the coroutine is cancelled. Caller ([MqttClient]) decides whether to loop.
     */
    suspend fun run() {
        val conn =
            try {
                connectAndHandshake()
            } finally {
                // Unblock start() whether we handshook or threw — the first attempt pass is
                // observable either way.
                firstAttemptComplete.complete(Unit)
            }
        try {
            coroutineScope {
                // The processor / ping-timer / write-loop children loop indefinitely. Cancel
                // them explicitly when the main receive flow exits (clean EOF after server
                // closes, or our own sendDisconnect → server FIN) so `coroutineScope` can
                // actually return — otherwise it waits forever for the infinite children and
                // the outer reconnect loop in MqttClient never gets to re-invoke run().
                val children =
                    listOf(
                        launch { processor.processIncomingMessages() },
                        launch { processor.runPingTimer() },
                        launch { writeLoop(conn) },
                    )
                try {
                    conn.receive().collect { packet -> readChannel.emit(packet) }
                } finally {
                    children.forEach { it.cancel() }
                }
            }
        } finally {
            withContext(NonCancellable) {
                _connectionState.value = ConnectionState.Disconnected
                // Clear the cached CONNACK so awaitConnectivity() waits for the next one on
                // reconnect rather than returning a stale ack from the session we just left.
                currentConnack = null
                conn.close()
            }
        }
    }

    private suspend fun connectAndHandshake(): Connection<ControlPacket> {
        var lastException: Throwable? = null
        for (connectionOp in broker.connectionOps) {
            connectionAttempts++
            val conn =
                try {
                    connectSingle(connectionOp, publishCodecForTopic)
                } catch (e: CancellationException) {
                    throw e
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Throwable,
                ) {
                    lastException = e
                    continue
                }
            try {
                _connectionState.value = ConnectionState.Handshaking
                conn.send(broker.connectionRequest as ControlPacket)
                // Bound the CONNACK wait explicitly. The socket now uses ReadPolicy.UntilClosed (a
                // persistent MQTT stream has no per-read deadline), so the handshake would otherwise
                // block forever against an unresponsive broker. withTimeoutOrNull (not withTimeout)
                // so a slow option falls through to the next failover option instead of cancelling.
                val response = withTimeoutOrNull(connectionOp.connectionTimeout) { conn.receive().first() }
                if (response == null) {
                    conn.close()
                    lastException =
                        MqttConnectionException.ProtocolError(
                            "Timed out after ${connectionOp.connectionTimeout} awaiting CONNACK",
                        )
                    continue
                }
                if (response is IConnectionAcknowledgment && response.isSuccessful) {
                    connectionCount++
                    currentConnack = response
                    _connectionState.value = ConnectionState.Connected(response)
                    prepareSession(response)
                    connectionBroadcastInternal.emit(response)
                    return conn
                }
                conn.close()
                lastException =
                    if (response is IConnectionAcknowledgment) {
                        MqttConnectionException.ConnackRejected(
                            "CONNACK rejected: ${response.connectionReason}",
                            response.byte1,
                        )
                    } else {
                        MqttConnectionException.ProtocolError(
                            "Expected CONNACK, got ${response::class.simpleName}",
                        )
                    }
            } catch (e: MqttConnectionException) {
                conn.close()
                lastException = e
            } catch (e: CancellationException) {
                conn.close()
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                conn.close()
                lastException = e
            }
        }
        throw lastException ?: IllegalStateException("No connection options configured for broker ${broker.brokerId}")
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
            processor.replayIncomingMessagesOnReconnect()
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
    }

    suspend fun sendDisconnect() {
        // Invalidate the cached CONNACK synchronously so `awaitConnectivity()` called right
        // after `sendDisconnect()` doesn't race against the write loop and return the ack of
        // the session we're tearing down. The outer reconnect loop will populate a fresh ack
        // after the next successful handshake.
        currentConnack = null
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
