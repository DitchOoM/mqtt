package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer

import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.mqtt.controlpacket.IPublishComplete
import com.ditchoom.mqtt.controlpacket.format.fixed.DirectionOfFlow
import kotlin.jvm.JvmInline

/**
 * 3.7 PUBCOMP – Publish complete (QoS 2 delivery part 3)
 *
 * The PUBCOMP packet is the response to a PUBREL packet. It is the fourth and final packet of the QoS 2 protocol exchange.
 */
@ProtocolMessage
@JvmInline
value class PublishComplete(
    val packetId: UShort,
) : ControlPacketV4,
    IPublishComplete {
    override val packetIdentifier: Int get() = packetId.toInt()
    override val controlPacketValue: Byte get() = IPublishComplete.CONTROL_PACKET_VALUE
    override val direction: DirectionOfFlow get() = DirectionOfFlow.BIDIRECTIONAL

    override fun encodeBody(writeBuffer: WriteBuffer) = PublishCompleteCodec.encode(writeBuffer, this)

    override fun remainingLength() = UShort.SIZE_BYTES

    companion object {
        fun from(buffer: ReadBuffer) = PublishCompleteCodec.decode(buffer)
    }
}
