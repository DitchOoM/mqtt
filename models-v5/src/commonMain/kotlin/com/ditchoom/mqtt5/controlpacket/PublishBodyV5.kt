package com.ditchoom.mqtt5.controlpacket

import com.ditchoom.buffer.codec.annotations.LengthPrefix
import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.Payload
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes
import com.ditchoom.mqtt5.controlpacket.properties.MqttProperty

/**
 * MQTT 5.0 PUBLISH body (post fixed-header byte1 + VBI remaining-length) for QoS 0.
 * No packet identifier field per [MQTT-2.3.1-5].
 *
 * Codec delivers [payload] via a caller-supplied `(PayloadReader) -> P` lambda on decode
 * and a `(WriteBuffer, P) -> Unit` lambda on encode.
 */
@ProtocolMessage
data class PublishBodyV5Qos0<@Payload P>(
    @LengthPrefixed val topic: String,
    @LengthPrefixed(LengthPrefix.Varint, maxBytes = 4) val properties: List<MqttProperty> = emptyList(),
    @RemainingBytes val payload: P,
)

/**
 * MQTT 5.0 PUBLISH body for QoS 1 or 2. Packet identifier is always present.
 */
@ProtocolMessage
data class PublishBodyV5QosNonZero<@Payload P>(
    @LengthPrefixed val topic: String,
    val packetIdentifier: UShort,
    @LengthPrefixed(LengthPrefix.Varint, maxBytes = 4) val properties: List<MqttProperty> = emptyList(),
    @RemainingBytes val payload: P,
)
