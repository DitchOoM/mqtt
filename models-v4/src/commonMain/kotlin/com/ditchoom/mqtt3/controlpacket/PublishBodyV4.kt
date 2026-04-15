package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.Payload
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes

/**
 * MQTT 3.1.1 PUBLISH body (post fixed-header byte1 + VBI remaining-length) for QoS 0.
 * No packet identifier field per [MQTT-2.3.1-5].
 *
 * Codec delivers [payload] via a caller-supplied `(PayloadReader) -> P` lambda on decode
 * and a `(WriteBuffer, P) -> Unit` lambda on encode.
 */
@ProtocolMessage
data class PublishBodyV4Qos0<@Payload P>(
    @LengthPrefixed val topic: String,
    @RemainingBytes val payload: P,
)

/**
 * MQTT 3.1.1 PUBLISH body for QoS 1 or 2. Packet identifier is always present.
 */
@ProtocolMessage
data class PublishBodyV4QosNonZero<@Payload P>(
    @LengthPrefixed val topic: String,
    val packetIdentifier: UShort,
    @RemainingBytes val payload: P,
)
