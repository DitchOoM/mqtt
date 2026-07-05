package com.ditchoom.mqtt.client.net

import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.Payload
import com.ditchoom.buffer.flow.Connection
import com.ditchoom.mqtt.connection.MqttBroker
import com.ditchoom.mqtt.connection.MqttConnectionOptions
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.ControlPacketFactory

internal typealias TestConnectSingle =
    suspend (MqttConnectionOptions, (topicName: String) -> Codec<out Payload>?) -> Connection<ControlPacket>

/**
 * Test-facing alias for [defaultSingleConnection] — the production per-option connector lives
 * in commonMain. Kept here so existing test call sites (`createConnectFactory(broker)`) read
 * as "give me the factory my client wants" without leaking the internal name. The
 * `publishCodecForTopic` lookup is supplied by `ConnectivityManager` at invocation time
 * (sourced from `MqttClient`'s `TopicCodecRegistry`), so wiring it in here would shadow
 * the per-client registry — pass it through.
 */
fun createConnectFactory(broker: MqttBroker): TestConnectSingle = defaultSingleConnection(broker)

fun createConnectFactory(factory: ControlPacketFactory): TestConnectSingle =
    { op, publishCodecForTopic -> defaultSingleConnection(op, factory, publishCodecForTopic) }
