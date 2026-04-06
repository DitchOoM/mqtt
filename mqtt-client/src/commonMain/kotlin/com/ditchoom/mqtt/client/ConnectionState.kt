package com.ditchoom.mqtt.client

import com.ditchoom.mqtt.controlpacket.IConnectionAcknowledgment

/**
 * MQTT-level connection state. Exposed as `StateFlow<ConnectionState>` on [MqttClient].
 *
 * Transport-level concerns (reconnection, backoff, network availability) belong to the
 * caller's [com.ditchoom.buffer.flow.Connection] wrapper — not here.
 */
sealed interface ConnectionState {
    /** No MQTT session active. Initial state and state after clean shutdown. */
    data object Disconnected : ConnectionState

    /** CONNECT packet sent, waiting for CONNACK. */
    data object Handshaking : ConnectionState

    /** CONNACK received with success — session is active. */
    data class Connected(val connack: IConnectionAcknowledgment) : ConnectionState
}
