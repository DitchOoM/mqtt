package com.ditchoom.mqtt.client

import com.ditchoom.mqtt.controlpacket.IConnectionAcknowledgment
import kotlin.time.Duration

/**
 * Observable state of the MQTT connection. Exposed as `StateFlow<ConnectionState>`
 * on [MqttClient] — the consumer can collect this to update UI or make decisions.
 *
 * Sealed: impossible to observe states that don't exist (e.g., no "Reconnecting" if
 * the connection factory doesn't support reconnection).
 */
sealed interface ConnectionState {
    /** No connection active. Initial state and state after clean shutdown. */
    data object Disconnected : ConnectionState

    /** TCP/WebSocket connection established, MQTT handshake in progress. */
    data object Connecting : ConnectionState

    /** CONNACK received with success. */
    data class Connected(val connack: IConnectionAcknowledgment) : ConnectionState

    /** Connection lost, will retry. Only possible if the caller provided a reconnecting connection. */
    data class Reconnecting(val attempt: Int, val nextRetryIn: Duration) : ConnectionState

    /** Non-recoverable error. Connection will not be retried. */
    data class Failed(val cause: Throwable) : ConnectionState
}
