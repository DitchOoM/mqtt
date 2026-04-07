package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer

import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.mqtt.controlpacket.IPublishReceived
import com.ditchoom.mqtt.controlpacket.format.ReasonCode
import com.ditchoom.mqtt.controlpacket.format.fixed.DirectionOfFlow
import kotlin.jvm.JvmInline

/**
 * 3.5 PUBREC – Publish received (QoS 2 delivery part 1)
 *
 * A PUBREC packet is the response to a PUBLISH packet with QoS 2. It is the second packet of the QoS 2 protocol exchange.
 */
@ProtocolMessage
@JvmInline
value class PublishReceived(
    val packetId: UShort,
) : ControlPacketV4,
    IPublishReceived {
    override val packetIdentifier: Int get() = packetId.toInt()
    override val controlPacketValue: Byte get() = IPublishReceived.CONTROL_PACKET_VALUE
    override val direction: DirectionOfFlow get() = DirectionOfFlow.BIDIRECTIONAL

    override fun encodeBody(writeBuffer: WriteBuffer) = PublishReceivedCodec.encode(writeBuffer, this)

    override fun remainingLength() = UShort.SIZE_BYTES

    override fun expectedResponse(
        reasonCode: ReasonCode,
        reasonString: String?,
        userProperty: List<Pair<String, String>>,
    ) = PublishRelease(packetId)

    companion object {
        fun from(buffer: ReadBuffer) = PublishReceivedCodec.decode(buffer)
    }
}
