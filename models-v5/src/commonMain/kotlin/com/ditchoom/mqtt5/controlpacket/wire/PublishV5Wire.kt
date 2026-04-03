package com.ditchoom.mqtt5.controlpacket.wire

import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.Payload
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes
import com.ditchoom.mqtt.codec.annotations.MqttProperties
import com.ditchoom.mqtt5.controlpacket.properties.MqttProperty

@ProtocolMessage
data class PublishWithIdV5Wire<@Payload P>(
    @LengthPrefixed val topicName: String,
    val packetId: UShort,
    @MqttProperties val properties: Collection<MqttProperty>?,
    @RemainingBytes val payload: P,
)

@ProtocolMessage
data class PublishNoIdV5Wire<@Payload P>(
    @LengthPrefixed val topicName: String,
    @MqttProperties val properties: Collection<MqttProperty>?,
    @RemainingBytes val payload: P,
)
