package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.mqtt.controlpacket.IPublishComplete
import com.ditchoom.mqtt.controlpacket.WireEncoded
import com.ditchoom.mqtt.controlpacket.format.fixed.DirectionOfFlow
import com.ditchoom.mqtt3.controlpacket.wire.AckWire
import com.ditchoom.mqtt3.controlpacket.wire.AckWireCodec
import kotlin.jvm.JvmInline

/**
 * 3.7 PUBCOMP – Publish complete (QoS 2 delivery part 3)
 *
 * The PUBCOMP packet is the response to a PUBREL packet. It is the fourth and final packet of the QoS 2 protocol exchange.
 */
@JvmInline
value class PublishComplete(
    override val packetIdentifier: Int,
) : ControlPacketV4,
    IPublishComplete,
    WireEncoded<AckWire> {
    override val controlPacketValue: Byte get() = IPublishComplete.CONTROL_PACKET_VALUE
    override val direction: DirectionOfFlow get() = DirectionOfFlow.BIDIRECTIONAL
    override val wireCodec: Codec<AckWire> get() = AckWireCodec

    override fun toWire() = AckWire(packetIdentifier.toUShort())

    companion object {
        fun from(buffer: ReadBuffer) = PublishComplete(AckWireCodec.decode(buffer).packetId.toInt())
    }
}
