package com.ditchoom.mqtt.client

import com.ditchoom.mqtt.connection.MqttConnectionOptions
import com.ditchoom.socket.SocketException

class UnavailableMqttServiceException(connectionOptions: Collection<MqttConnectionOptions>, cause: Throwable? = null) :
    SocketException(
        connectionOptions.joinToString(
            prefix = "Failed to connect to services:",
            postfix = (" " + cause?.message)
        ),
        cause
    )
