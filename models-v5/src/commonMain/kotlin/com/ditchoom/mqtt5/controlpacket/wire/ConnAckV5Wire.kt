package com.ditchoom.mqtt5.controlpacket.wire

import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.mqtt.codec.annotations.MqttProperties
import com.ditchoom.mqtt5.controlpacket.properties.MqttProperty

/**
 * Wire model for MQTT v5 CONNACK packet variable header.
 * Includes acknowledge flags, reason code, and VBI-prefixed properties section.
 */
@ProtocolMessage
data class ConnAckV5Wire(
    val acknowledgeFlags: UByte,
    val reasonCode: UByte,
    @MqttProperties val properties: Collection<MqttProperty>?,
)
