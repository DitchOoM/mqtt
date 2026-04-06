package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.mqtt.controlpacket.IPublishRelease
import com.ditchoom.mqtt.controlpacket.WireEncoded
import com.ditchoom.mqtt.controlpacket.format.ReasonCode
import com.ditchoom.mqtt.controlpacket.format.fixed.DirectionOfFlow
import com.ditchoom.mqtt3.controlpacket.wire.AckWire
import com.ditchoom.mqtt3.controlpacket.wire.AckWireCodec
import kotlin.jvm.JvmInline

/**
 * 3.6 PUBREL – Publish release (QoS 2 delivery part 2)
 *
 * A PUBREL packet is the response to a PUBREC packet. It is the third packet of the QoS 2 protocol exchange.
 */
@JvmInline
value class PublishRelease(
    override val packetIdentifier: Int,
) : ControlPacketV4,
    IPublishRelease,
    WireEncoded<AckWire> {
    override val controlPacketValue: Byte get() = IPublishRelease.CONTROL_PACKET_VALUE
    override val direction: DirectionOfFlow get() = DirectionOfFlow.BIDIRECTIONAL
    override val flags: Byte get() = 0b10
    override val wireCodec: Codec<AckWire> get() = AckWireCodec

    override fun toWire() = AckWire(packetIdentifier.toUShort())

    override fun expectedResponse(
        reasonCode: ReasonCode,
        reasonString: String?,
        userProperty: List<Pair<String, String>>,
    ) = PublishComplete(packetIdentifier)

    companion object {
        fun from(buffer: ReadBuffer) = PublishRelease(AckWireCodec.decode(buffer).packetId.toInt())
    }
}
