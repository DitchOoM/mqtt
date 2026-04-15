package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes
import com.ditchoom.buffer.utf8Length
import com.ditchoom.mqtt.ProtocolError
import com.ditchoom.mqtt.controlpacket.IUnsubscribeRequest
import com.ditchoom.mqtt.controlpacket.TopicFilter
import com.ditchoom.mqtt.controlpacket.format.fixed.DirectionOfFlow

/**
 * Wire model for a single topic filter entry (length-prefixed string).
 */
@ProtocolMessage
data class TopicFilterEntry(
    @LengthPrefixed val filter: String,
)

/**
 * 3.10 UNSUBSCRIBE – Unsubscribe request
 * An UNSUBSCRIBE packet is sent by the Client to the Server, to unsubscribe from topics.
 */
@ProtocolMessage
data class UnsubscribeRequest(
    val packetId: UShort,
    @RemainingBytes val topicEntries: List<TopicFilterEntry>,
) : ControlPacketV4,
    IUnsubscribeRequest {
    override val packetIdentifier: Int get() = packetId.toInt()
    override val topics: Set<TopicFilter> get() = topicEntries.map { TopicFilter.fromOrThrow(it.filter) }.toSet()
    override val controlPacketValue: Byte get() = IUnsubscribeRequest.controlPacketValue
    override val direction: DirectionOfFlow get() = DirectionOfFlow.CLIENT_TO_SERVER
    override val flags: Byte get() = 0b10

    constructor(packetIdentifier: Int, topics: Set<TopicFilter>) :
        this(packetIdentifier.toUShort(), topics.map { TopicFilterEntry(it.toString()) })

    constructor(packetIdentifier: Int, topicString: Collection<String>) :
        this(packetIdentifier.toUShort(), topicString.map { TopicFilterEntry(it) })

    init {
        if (topicEntries.isEmpty()) {
            throw ProtocolError("An UNSUBSCRIBE packet with no Payload is a Protocol Error")
        }
    }

    override fun remainingLength() = UShort.SIZE_BYTES + topicEntries.sumOf { it.filter.utf8Length() + UShort.SIZE_BYTES }

    override fun encodeBody(writeBuffer: WriteBuffer) = UnsubscribeRequestCodec.encode(writeBuffer, this)

    override fun copyWithNewPacketIdentifier(packetIdentifier: Int): IUnsubscribeRequest = copy(packetId = packetIdentifier.toUShort())

    companion object {
        fun from(
            buffer: ReadBuffer,
            remainingLength: Int,
        ): UnsubscribeRequest {
            val sliced = buffer.readBytes(remainingLength)
            return UnsubscribeRequestCodec.decode(sliced)
        }
    }
}
