package com.ditchoom.mqtt3.controlpacket.wire

import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes

/**
 * Wire model for a single subscription entry: topic filter + requested QoS byte.
 */
@ProtocolMessage
data class SubscriptionWire(
    @LengthPrefixed val topicFilter: String,
    val requestedQos: UByte,
)

/**
 * Wire model for SUBSCRIBE packet body: packet identifier followed by subscription list.
 */
@ProtocolMessage
data class SubscribeWire(
    val packetIdentifier: UShort,
    @RemainingBytes val subscriptions: List<SubscriptionWire>,
)
