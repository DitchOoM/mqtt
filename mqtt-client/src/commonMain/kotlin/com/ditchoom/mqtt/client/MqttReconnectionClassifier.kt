package com.ditchoom.mqtt.client

import com.ditchoom.socket.DefaultReconnectionClassifier
import com.ditchoom.socket.ReconnectDecision.GiveUp
import com.ditchoom.socket.ReconnectionClassifier

class MqttReconnectionClassifier(
    private val delegate: DefaultReconnectionClassifier = DefaultReconnectionClassifier(),
) : ReconnectionClassifier {
    override suspend fun classify(error: Throwable) =
        when (error) {
            is MqttConnectionException.ConnackRejected -> GiveUp
            is MqttConnectionException.ProtocolError -> GiveUp
            is MqttConnectionException.AllEndpointsFailed ->
                if (error.allNonRecoverable) GiveUp else delegate.classify(error)
            is MqttConnectionException.TransportFailed -> delegate.classify(error.cause!!)
            else -> delegate.classify(error)
        }

    fun reset() = delegate.reset()
}
