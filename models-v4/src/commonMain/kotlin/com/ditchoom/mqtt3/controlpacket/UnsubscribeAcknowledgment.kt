package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.mqtt.controlpacket.IUnsubscribeAcknowledgment
import com.ditchoom.mqtt.controlpacket.format.fixed.DirectionOfFlow
import kotlin.jvm.JvmInline

@ProtocolMessage
@JvmInline
value class UnsubscribeAcknowledgment(
    val packetId: UShort,
) : ControlPacketV4,
    IUnsubscribeAcknowledgment {
    override val packetIdentifier: Int get() = packetId.toInt()
    override val controlPacketValue: Byte get() = IUnsubscribeAcknowledgment.CONTROL_PACKET_VALUE
    override val direction: DirectionOfFlow get() = DirectionOfFlow.SERVER_TO_CLIENT

    override fun encodeBody(writeBuffer: WriteBuffer) = UnsubscribeAcknowledgmentCodec.encode(writeBuffer, this)

    override fun remainingLength() = UShort.SIZE_BYTES

    companion object {
        fun from(buffer: ReadBuffer) = UnsubscribeAcknowledgmentCodec.decode(buffer)
    }
}
