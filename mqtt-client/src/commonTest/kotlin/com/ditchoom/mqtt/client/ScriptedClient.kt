package com.ditchoom.mqtt.client

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.mqtt.Persistence
import com.ditchoom.mqtt.client.net.MqttConnector
import com.ditchoom.mqtt.connection.MqttBroker
import kotlinx.coroutines.CoroutineScope
import kotlin.time.TimeSource

/** Starts a client whose every connect goes to [connector] instead of a real transport. */
internal suspend fun startScriptedClient(
    scope: CoroutineScope,
    broker: MqttBroker,
    persistence: Persistence,
    connector: MqttConnector,
): MqttClient =
    MqttClient.start(
        scope,
        broker,
        persistence,
        listOf(MqttEndpoint("scripted", 1883)),
        BufferFactory.Default,
        InFlightLimit.ProtocolMaximum,
        TimeSource.Monotonic,
        connector,
    )
