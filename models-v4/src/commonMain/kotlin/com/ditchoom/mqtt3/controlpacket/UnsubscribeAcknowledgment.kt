package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.mqtt.controlpacket.IUnsubscribeAcknowledgment
import com.ditchoom.mqtt.controlpacket.WireEncoded
import com.ditchoom.mqtt.controlpacket.format.fixed.DirectionOfFlow
import com.ditchoom.mqtt3.controlpacket.wire.AckWire
import com.ditchoom.mqtt3.controlpacket.wire.AckWireCodec
import kotlin.jvm.JvmInline

@JvmInline
value class UnsubscribeAcknowledgment(
    override val packetIdentifier: Int,
) : ControlPacketV4,
    IUnsubscribeAcknowledgment,
    WireEncoded<AckWire> {
    override val controlPacketValue: Byte get() = IUnsubscribeAcknowledgment.CONTROL_PACKET_VALUE
    override val direction: DirectionOfFlow get() = DirectionOfFlow.SERVER_TO_CLIENT
    override val wireCodec: Codec<AckWire> get() = AckWireCodec

    override fun toWire() = AckWire(packetIdentifier.toUShort())

    companion object {
        fun from(buffer: ReadBuffer) = UnsubscribeAcknowledgment(AckWireCodec.decode(buffer).packetId.toInt())
    }
}
