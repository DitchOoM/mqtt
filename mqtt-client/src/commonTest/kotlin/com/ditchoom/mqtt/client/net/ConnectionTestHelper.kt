package com.ditchoom.mqtt.client.net

import com.ditchoom.buffer.flow.Connection
import com.ditchoom.mqtt.connection.MqttBroker
import com.ditchoom.mqtt.connection.MqttConnectionOptions
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.ControlPacketFactory

/**
 * Test-facing aliases for [defaultConnectionFactory] — the production factory
 * (same behaviour) lives in commonMain now. Kept here so existing test call sites
 * (`createConnectFactory(broker)`) don't need to churn.
 */
fun createConnectFactory(broker: MqttBroker): suspend () -> Connection<ControlPacket> =
    defaultConnectionFactory(broker)

fun createConnectFactory(
    connectionOps: Collection<MqttConnectionOptions>,
    factory: ControlPacketFactory,
): suspend () -> Connection<ControlPacket> = defaultConnectionFactory(connectionOps, factory)
