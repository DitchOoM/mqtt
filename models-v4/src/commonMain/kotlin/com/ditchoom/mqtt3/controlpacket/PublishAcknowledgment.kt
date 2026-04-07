package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.mqtt.controlpacket.IPublishAcknowledgment
import com.ditchoom.mqtt.controlpacket.format.fixed.DirectionOfFlow
import kotlin.jvm.JvmInline

/**
 * 3.4 PUBACK – Publish acknowledgement
 *
 * A PUBACK packet is the response to a PUBLISH packet with QoS 1.
 */
@ProtocolMessage
@JvmInline
value class PublishAcknowledgment(
    val packetId: UShort,
) : ControlPacketV4,
    IPublishAcknowledgment {
    override val packetIdentifier: Int get() = packetId.toInt()
    override val controlPacketValue: Byte get() = IPublishAcknowledgment.CONTROL_PACKET_VALUE
    override val direction: DirectionOfFlow get() = DirectionOfFlow.BIDIRECTIONAL

    override fun encodeBody(writeBuffer: WriteBuffer) = PublishAcknowledgmentCodec.encode(writeBuffer, this)

    override fun remainingLength() = UShort.SIZE_BYTES

    companion object {
        fun from(buffer: ReadBuffer) = PublishAcknowledgmentCodec.decode(buffer)
    }
}
