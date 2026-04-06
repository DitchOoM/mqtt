package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.mqtt.controlpacket.IPublishReceived
import com.ditchoom.mqtt.controlpacket.WireEncoded
import com.ditchoom.mqtt.controlpacket.format.ReasonCode
import com.ditchoom.mqtt.controlpacket.format.fixed.DirectionOfFlow
import com.ditchoom.mqtt3.controlpacket.wire.AckWire
import com.ditchoom.mqtt3.controlpacket.wire.AckWireCodec
import kotlin.jvm.JvmInline

/**
 * 3.5 PUBREC – Publish received (QoS 2 delivery part 1)
 *
 * A PUBREC packet is the response to a PUBLISH packet with QoS 2. It is the second packet of the QoS 2 protocol exchange.
 */
@JvmInline
value class PublishReceived(
    override val packetIdentifier: Int,
) : ControlPacketV4,
    IPublishReceived,
    WireEncoded<AckWire> {
    override val controlPacketValue: Byte get() = IPublishReceived.CONTROL_PACKET_VALUE
    override val direction: DirectionOfFlow get() = DirectionOfFlow.BIDIRECTIONAL
    override val wireCodec: Codec<AckWire> get() = AckWireCodec

    override fun toWire() = AckWire(packetIdentifier.toUShort())

    override fun expectedResponse(
        reasonCode: ReasonCode,
        reasonString: String?,
        userProperty: List<Pair<String, String>>,
    ) = PublishRelease(packetIdentifier.toUShort().toInt())

    companion object {
        fun from(buffer: ReadBuffer) = PublishReceived(AckWireCodec.decode(buffer).packetId.toInt())
    }
}
