package com.ditchoom.mqtt.client

import com.ditchoom.mqtt.Persistence
import com.ditchoom.mqtt.connection.MqttBroker
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.IConnectionAcknowledgment
import com.ditchoom.mqtt.controlpacket.IDisconnectNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class ConnectivityManager(
    internal val scope: CoroutineScope,
    private val persistence: Persistence,
    internal val broker: MqttBroker
) {
    var connectionCount = 0L
        private set
    var connectionAttempts = 0L
        private set

    var observer: Observer? = null
        set(value) {
            currentSocketSession?.observer = value
            field = value
        }

    private var incomingProcessingJob: Job? = null
    private var isStopped = false
    private val readChannel = MutableSharedFlow<ControlPacket>()
    private val writeChannel = Channel<Collection<ControlPacket>>(Channel.BUFFERED)
    private val connectionBroadcastChannelInternal = MutableSharedFlow<IConnectionAcknowledgment>()
    val connectionBroadcastChannel: SharedFlow<IConnectionAcknowledgment> = connectionBroadcastChannelInternal

    private var currentConnectionJob: Job? = null
    val processor = ControlPacketProcessor(scope, broker, readChannel, writeChannel, persistence)
    private var currentSocketSession: MqttSocketSession? = null

    fun currentConnack(): IConnectionAcknowledgment? = currentSocketSession?.connectionAcknowledgement

    fun stayConnected(
        initialDelay: Duration = 0.1.seconds, // 0.1 second
        maxDelay: Duration = 15.seconds, // 15 second
        factor: Double = 2.0
    ) {
        currentConnectionJob?.cancel()
        currentConnectionJob = null
        var currentDelay = initialDelay
        val job = scope.launch {
            while (isActive && !isStopped) {
                val result = buildConnectionShouldRetry(this)
                processor.cancelPingTimer()
                if (!result.shouldContinueReconnecting) {
                    observer?.stopReconnecting(broker.identifier, result)
                    break
                }
                currentDelay = if (result.shouldResetTimer || isStopped) {
                    observer?.reconnectAndResetTimer(broker.identifier, result)
                    initialDelay
                } else {
                    observer?.reconnectIn(broker.identifier, currentDelay, result)
                    delay(currentDelay)
                    (currentDelay * factor).coerceAtMost(maxDelay)
                }
            }
            currentConnectionJob = null
        }
        currentConnectionJob = job
    }

    suspend fun shutdown(sendDisconnect: Boolean = true) {
        if (isStopped) return
        isStopped = true
        try {
            if (sendDisconnect) {
                sendDisconnect()
            }
        } finally {
            currentSocketSession?.close()
            processor.cancelPingTimer()
            incomingProcessingJob?.cancel()
            incomingProcessingJob = null
            writeChannel.close()
            currentConnectionJob?.cancel()
            currentConnectionJob = null
            observer?.shutdown()
        }
    }

    private suspend fun connectMqttSocketSessionOrThrow(): MqttSocketSession {
        var lastException: Throwable? = null
        for (connectionOp in broker.connectionOps) {
            val session = try {
                withTimeout(connectionOp.connectionTimeout) {
                    connectionAttempts++
                    observer?.openSocketSession(broker.connectionRequest, connectionOp)
                    val socketSession = MqttSocketSession.open(broker.identifier, broker.connectionRequest, connectionOp, observer)
                    if (socketSession.connectionAcknowledgement.isSuccessful) {
                        socketSession
                    } else {
                        null
                    }
                }
            } catch (e: Throwable) {
                lastException = e
                null
            } ?: continue
            return session
        }
        throw UnavailableMqttServiceException(broker.connectionOps, lastException)
    }

    suspend fun connectOnce() {
        val socketSession = connectMqttSocketSessionOrThrow()
        currentSocketSession = socketSession
        connectionCount++
        prepareSocketSession(socketSession)
        incomingProcessingJob = scope.launch {
            processor.processIncomingMessages()
        }
        scope.launch {
            while (isActive && socketSession.isOpen() && !isStopped) {
                val packetToWrite = try {
                    writeChannel.receive()
                } catch (e: Exception) {
                    observer?.connectOnceWriteChannelReceiveException(broker.identifier, e)
                    shutdown()
                    return@launch
                }
                try {
                    socketSession.write(packetToWrite)
                } catch (e: Exception) {
                    // ignore
                    observer?.connectOnceSocketSessionWriteException(broker.identifier, e)
                    shutdown()
                    return@launch
                }
                if (packetToWrite is IDisconnectNotification) {
                    shutdown()
                    return@launch
                }
            }
        }
        scope.launch {
            try {
                socketSession.incomingPacketFlow.collect {
                    readChannel.emit(it)
                }
            } finally {
                shutdown()
            }
        }
    }

    private suspend fun prepareSocketSession(socketSession: MqttSocketSession): ConnectionEndReason? {
        processor.resetPingTimer()
        val sessionPresent = socketSession.connectionAcknowledgement.sessionPresent
        if (broker.connectionRequest.cleanStart && sessionPresent) {
            socketSession.close()
            return ConnectionEndReason(
                shouldContinueReconnecting = false, shouldResetTimer = true,
                msg = "[MQTT-3.2.2-4] failure. Try reconnecting with cleanStart = true"
            )
        }
        if (sessionPresent) {
            emptyWriteChannel()
            val messages = processor.queueMessagesOnReconnect()
            if (messages.isNotEmpty()) {
                socketSession.write(messages)
            }
        } else {
            persistence.clearMessages(broker)
        }
        connectionBroadcastChannelInternal.emit(socketSession.connectionAcknowledgement)
        return null
    }

    private suspend fun buildConnectionShouldRetry(scope: CoroutineScope): ConnectionEndReason {
        var writeJob: Job? = null
        val processingJob = scope.launch {
            processor.processIncomingMessages()
        }
        try {
            val socketSession = try {
                connectMqttSocketSessionOrThrow()
            } catch (e: UnavailableMqttServiceException) {
                return ConnectionEndReason(
                    shouldContinueReconnecting = true,
                    shouldResetTimer = false,
                    msg = e.message
                )
            }
            connectionCount++
            currentSocketSession = socketSession
            val endReason = prepareSocketSession(socketSession)
            if (endReason != null) {
                return endReason
            }
            writeJob = scope.launch {
                while (isActive && socketSession.isOpen()) {
                    try {
                        val packetToWrite = writeChannel.receive()
                        socketSession.write(packetToWrite)
                    } catch (e: Exception) {
                        // ignore
                        break
                    }
                }
            }
            socketSession.incomingPacketFlow.collect {
                readChannel.emit(it)
            }
        } catch (e: Exception) {
            return ConnectionEndReason(
                shouldContinueReconnecting = true,
                shouldResetTimer = false,
                msg = e.message
            )
        } finally {
            currentSocketSession = null
            writeJob?.cancel()
            processingJob.cancel()
        }
        return ConnectionEndReason(
            shouldContinueReconnecting = true,
            shouldResetTimer = true,
            msg = "Normal disconnect"
        )
    }

    private fun emptyWriteChannel() {
        // empty all the write buffers with the new connection
        var shouldContinueEmptyingChannel = writeChannel.tryReceive().isSuccess
        while (shouldContinueEmptyingChannel) {
            shouldContinueEmptyingChannel = writeChannel.tryReceive().isSuccess
        }
    }

    suspend fun sendDisconnect() {
        val disconnect = broker.connectionRequest.controlPacketFactory.disconnect()
        val socketSession = currentSocketSession
        if (socketSession != null && socketSession.isOpen()) {
            try {
                socketSession.write(listOf(disconnect))
            } catch (e: Exception) {
                // ignore
            }
        }
        socketSession?.close()
    }

    data class ConnectionEndReason(val shouldContinueReconnecting: Boolean, val shouldResetTimer: Boolean, val msg: String?)
}
