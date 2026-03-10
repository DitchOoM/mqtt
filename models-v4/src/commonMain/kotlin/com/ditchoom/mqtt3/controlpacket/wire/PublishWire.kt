package com.ditchoom.mqtt3.controlpacket.wire

import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.Payload
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes

/**
 * Wire model for PUBLISH packets with QoS > 0 (has packet identifier).
 */
@ProtocolMessage
data class PublishWithIdWire<@Payload P>(
    @LengthPrefixed val topicName: String,
    val packetId: UShort,
    @RemainingBytes val payload: P,
)

/**
 * Wire model for PUBLISH packets with QoS 0 (no packet identifier).
 */
@ProtocolMessage
data class PublishNoIdWire<@Payload P>(
    @LengthPrefixed val topicName: String,
    @RemainingBytes val payload: P,
)
