package com.ditchoom.mqtt5.controlpacket.wire

import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.mqtt.codec.annotations.MqttProperties
import com.ditchoom.mqtt5.controlpacket.properties.Property

/**
 * Wire model shared by PUBACK, PUBREC, PUBREL, PUBCOMP v5 packets.
 * Only used when remainingLength > 2 (i.e., reason code and properties present).
 * When remainingLength == 2, the packet contains only packetId.
 */
@ProtocolMessage
data class AckV5Wire(
    val packetId: UShort,
    val reasonCode: UByte,
    @MqttProperties val properties: Collection<Property>?,
)
