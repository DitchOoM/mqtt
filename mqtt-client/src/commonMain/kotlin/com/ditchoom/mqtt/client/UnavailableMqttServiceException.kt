package com.ditchoom.mqtt.client

import com.ditchoom.mqtt.connection.MqttConnectionOptions

class UnavailableMqttServiceException(connectionOptions: Collection<MqttConnectionOptions>, cause: Throwable? = null) :
    Exception(
        connectionOptions.joinToString(
            prefix = "Failed to connect to services:",
            postfix = (" " + cause?.message)
        ),
        cause
    )
