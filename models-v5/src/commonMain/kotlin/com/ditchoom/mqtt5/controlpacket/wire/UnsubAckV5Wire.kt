package com.ditchoom.mqtt5.controlpacket.wire

import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes
import com.ditchoom.mqtt.codec.annotations.MqttProperties
import com.ditchoom.mqtt5.controlpacket.properties.Property
import kotlin.jvm.JvmInline

@ProtocolMessage
@JvmInline
value class UnsubAckReasonCodeV5Wire(val raw: UByte)

@ProtocolMessage
data class UnsubAckV5Wire(
    val packetIdentifier: UShort,
    @MqttProperties val properties: Collection<Property>?,
    @RemainingBytes val reasonCodes: List<UnsubAckReasonCodeV5Wire>,
)
