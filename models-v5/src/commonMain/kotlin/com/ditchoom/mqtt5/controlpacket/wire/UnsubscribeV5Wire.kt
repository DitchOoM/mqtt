package com.ditchoom.mqtt5.controlpacket.wire

import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes
import com.ditchoom.mqtt.codec.annotations.MqttProperties
import com.ditchoom.mqtt5.controlpacket.properties.MqttProperty

@ProtocolMessage
data class TopicFilterV5Wire(
    @LengthPrefixed val topicFilter: String,
)

@ProtocolMessage
data class UnsubscribeV5Wire(
    val packetIdentifier: UShort,
    @MqttProperties val properties: Collection<MqttProperty>?,
    @RemainingBytes val topics: List<TopicFilterV5Wire>,
)
