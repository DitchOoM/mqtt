package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer

import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.mqtt.controlpacket.IPublishRelease
import com.ditchoom.mqtt.controlpacket.format.ReasonCode
import com.ditchoom.mqtt.controlpacket.format.fixed.DirectionOfFlow
import kotlin.jvm.JvmInline

/**
 * 3.6 PUBREL – Publish release (QoS 2 delivery part 2)
 *
 * A PUBREL packet is the response to a PUBREC packet. It is the third packet of the QoS 2 protocol exchange.
 */
@ProtocolMessage
@JvmInline
value class PublishRelease(
    val packetId: UShort,
) : ControlPacketV4,
    IPublishRelease {
    override val packetIdentifier: Int get() = packetId.toInt()
    override val controlPacketValue: Byte get() = IPublishRelease.CONTROL_PACKET_VALUE
    override val direction: DirectionOfFlow get() = DirectionOfFlow.BIDIRECTIONAL
    override val flags: Byte get() = 0b10

    override fun encodeBody(writeBuffer: WriteBuffer) = PublishReleaseCodec.encode(writeBuffer, this)

    override fun remainingLength() = UShort.SIZE_BYTES

    override fun expectedResponse(
        reasonCode: ReasonCode,
        reasonString: String?,
        userProperty: List<Pair<String, String>>,
    ) = PublishComplete(packetId)

    companion object {
        fun from(buffer: ReadBuffer) = PublishReleaseCodec.decode(buffer)
    }
}
