package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.utf8Length
import com.ditchoom.mqtt.ProtocolError
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.writeMqttUtf8String
import com.ditchoom.mqtt.controlpacket.IUnsubscribeRequest
import com.ditchoom.mqtt.controlpacket.TopicFilter
import com.ditchoom.mqtt.controlpacket.format.fixed.DirectionOfFlow
import com.ditchoom.mqtt3.controlpacket.wire.TopicFilterWire
import com.ditchoom.mqtt3.controlpacket.wire.UnsubscribeWire
import com.ditchoom.mqtt3.controlpacket.wire.UnsubscribeWireCodec

/**
 * 3.10 UNSUBSCRIBE – Unsubscribe request
 * An UNSUBSCRIBE packet is sent by the Client to the Server, to unsubscribe from topics.
 */
data class UnsubscribeRequest(
    override val packetIdentifier: Int,
    override val topics: Set<TopicFilter>,
) : ControlPacketV4,
    IUnsubscribeRequest {
    override val controlPacketValue: Byte get() = IUnsubscribeRequest.controlPacketValue
    override val direction: DirectionOfFlow get() = DirectionOfFlow.CLIENT_TO_SERVER
    override val flags: Byte get() = 0b10
    constructor(packetIdentifier: Int, topicString: Collection<String>) :
        this(packetIdentifier, topicString.map { TopicFilter.fromOrThrow(it) }.toSet())

    override fun remainingLength() = UShort.SIZE_BYTES + payloadSize()

    override fun encodeBody(writeBuffer: WriteBuffer) {
        UnsubscribeWireCodec.encode(
            writeBuffer,
            UnsubscribeWire(
                packetIdentifier.toUShort(),
                topics.map { TopicFilterWire(it.toString()) },
            ),
        )
    }

    private fun payloadSize(): Int {
        var size = 0
        topics.forEach {
            size += UShort.SIZE_BYTES + it.toString().utf8Length()
        }
        return size
    }

    init {
        if (topics.isEmpty()) {
            throw ProtocolError("An UNSUBSCRIBE packet with no Payload is a Protocol Error")
        }
    }

    override fun copyWithNewPacketIdentifier(packetIdentifier: Int): IUnsubscribeRequest = copy(packetIdentifier = packetIdentifier)

    companion object {
        fun from(
            buffer: ReadBuffer,
            remainingLength: Int,
        ): UnsubscribeRequest {
            val sliced = buffer.readBytes(remainingLength)
            val wire = UnsubscribeWireCodec.decode(sliced)
            val topics = wire.topics.map { TopicFilter.fromOrThrow(it.topicFilter) }.toSet()
            return UnsubscribeRequest(wire.packetIdentifier.toInt(), topics)
        }
    }
}
