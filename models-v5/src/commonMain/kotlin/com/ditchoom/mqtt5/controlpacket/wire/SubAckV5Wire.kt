package com.ditchoom.mqtt5.controlpacket.wire

import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes
import com.ditchoom.mqtt.codec.annotations.MqttProperties
import com.ditchoom.mqtt5.controlpacket.properties.MqttProperty
import kotlin.jvm.JvmInline

@ProtocolMessage
@JvmInline
value class SubAckReasonCodeV5Wire(val raw: UByte)

@ProtocolMessage
data class SubAckV5Wire(
    val packetIdentifier: UShort,
    @MqttProperties val properties: Collection<MqttProperty>?,
    @RemainingBytes val returnCodes: List<SubAckReasonCodeV5Wire>,
)
