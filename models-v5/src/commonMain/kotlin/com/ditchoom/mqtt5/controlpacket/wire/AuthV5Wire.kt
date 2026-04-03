package com.ditchoom.mqtt5.controlpacket.wire

import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.mqtt.codec.annotations.MqttProperties
import com.ditchoom.mqtt5.controlpacket.properties.MqttProperty

@ProtocolMessage
data class AuthV5Wire(
    val reasonCode: UByte,
    @MqttProperties val properties: Collection<MqttProperty>?,
)
