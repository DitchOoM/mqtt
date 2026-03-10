package com.ditchoom.mqtt5.controlpacket.wire

import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.mqtt.codec.annotations.MqttProperties
import com.ditchoom.mqtt5.controlpacket.properties.Property

@ProtocolMessage
data class DisconnectV5Wire(
    val reasonCode: UByte,
    @MqttProperties val properties: Collection<Property>?,
)
