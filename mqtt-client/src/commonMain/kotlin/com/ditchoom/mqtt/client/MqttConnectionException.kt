package com.ditchoom.mqtt.client

sealed class MqttConnectionException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** Transport (TCP/TLS/WebSocket) failure. Original exception in .cause */
    class TransportFailed(
        message: String,
        cause: Throwable,
    ) : MqttConnectionException(message, cause)

    /** CONNACK rejected (bad credentials, not authorized, server unavailable) */
    class ConnackRejected(
        message: String,
        val reasonCode: UByte,
        cause: Throwable? = null,
    ) : MqttConnectionException(message, cause)

    /** All configured endpoints failed */
    class AllEndpointsFailed(
        message: String,
        val allNonRecoverable: Boolean,
        cause: Throwable? = null,
    ) : MqttConnectionException(message, cause)

    /** MQTT protocol error (malformed packet, unexpected packet type) */
    class ProtocolError(
        message: String,
        cause: Throwable? = null,
    ) : MqttConnectionException(message, cause)
}
