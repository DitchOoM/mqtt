package com.ditchoom.mqtt3.controlpacket.wire

import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes

/**
 * Wire model for a single topic filter entry (length-prefixed string).
 */
@ProtocolMessage
data class TopicFilterWire(
    @LengthPrefixed val topicFilter: String,
)

/**
 * Wire model for UNSUBSCRIBE packet body: packet identifier followed by topic filter list.
 */
@ProtocolMessage
data class UnsubscribeWire(
    val packetIdentifier: UShort,
    @RemainingBytes val topics: List<TopicFilterWire>,
)
