package com.ditchoom.mqtt.client

sealed class MqttConnectionException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** CONNACK rejected (bad credentials, not authorized, server unavailable) */
    class ConnackRejected(
        message: String,
        val reasonCode: UByte,
        cause: Throwable? = null,
    ) : MqttConnectionException(message, cause)

    /** MQTT protocol error (malformed packet, unexpected packet type) */
    class ProtocolError(
        message: String,
        cause: Throwable? = null,
    ) : MqttConnectionException(message, cause)
}
