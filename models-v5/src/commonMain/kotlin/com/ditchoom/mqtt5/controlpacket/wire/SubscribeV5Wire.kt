package com.ditchoom.mqtt5.controlpacket.wire

import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes
import com.ditchoom.mqtt.codec.annotations.MqttProperties
import com.ditchoom.mqtt5.controlpacket.properties.Property

@ProtocolMessage
data class SubscriptionV5Wire(
    @LengthPrefixed val topicFilter: String,
    val subscriptionOptions: UByte,
)

@ProtocolMessage
data class SubscribeV5Wire(
    val packetIdentifier: UShort,
    @MqttProperties val properties: Collection<Property>?,
    @RemainingBytes val subscriptions: List<SubscriptionV5Wire>,
)
