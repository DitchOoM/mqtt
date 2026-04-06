package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.mqtt.controlpacket.IPublishAcknowledgment
import com.ditchoom.mqtt.controlpacket.WireEncoded
import com.ditchoom.mqtt.controlpacket.format.fixed.DirectionOfFlow
import com.ditchoom.mqtt3.controlpacket.wire.AckWire
import com.ditchoom.mqtt3.controlpacket.wire.AckWireCodec
import kotlin.jvm.JvmInline

/**
 * 3.4 PUBACK – Publish acknowledgement
 *
 * A PUBACK packet is the response to a PUBLISH packet with QoS 1.
 */
@JvmInline
value class PublishAcknowledgment(
    override val packetIdentifier: Int,
) : ControlPacketV4,
    IPublishAcknowledgment,
    WireEncoded<AckWire> {
    override val controlPacketValue: Byte get() = IPublishAcknowledgment.CONTROL_PACKET_VALUE
    override val direction: DirectionOfFlow get() = DirectionOfFlow.BIDIRECTIONAL
    override val wireCodec: Codec<AckWire> get() = AckWireCodec

    override fun toWire() = AckWire(packetIdentifier.toUShort())

    companion object {
        fun from(buffer: ReadBuffer) = PublishAcknowledgment(AckWireCodec.decode(buffer).packetId.toInt())
    }
}
