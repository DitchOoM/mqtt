package com.ditchoom.mqtt.client.net

import com.ditchoom.buffer.flow.Connection
import com.ditchoom.mqtt.connection.MqttBroker
import com.ditchoom.mqtt.connection.MqttConnectionOptions
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.ControlPacketFactory

internal typealias TestConnectSingle = suspend (MqttConnectionOptions) -> Connection<ControlPacket>

/**
 * Test-facing alias for [defaultSingleConnection] — the production per-option connector lives
 * in commonMain. Kept here so existing test call sites (`createConnectFactory(broker)`) read
 * as "give me the factory my client wants" without leaking the internal name.
 */
fun createConnectFactory(broker: MqttBroker): TestConnectSingle = defaultSingleConnection(broker)

fun createConnectFactory(factory: ControlPacketFactory): TestConnectSingle = { op -> defaultSingleConnection(op, factory) }
