package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.mqtt.controlpacket.IPublishRelease
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
    IPublishRelease {
    override val controlPacketValue: Byte get() = IPublishRelease.CONTROL_PACKET_VALUE
    override val direction: DirectionOfFlow get() = DirectionOfFlow.BIDIRECTIONAL
    override val flags: Byte get() = 0b10

    override fun remainingLength() = 2

    override fun serialize(writeBuffer: WriteBuffer) {
        writeBuffer.writeInt(PUBREL_HEADER or (packetIdentifier and PACKET_ID_MASK))
    }

    override fun encodeBody(writeBuffer: WriteBuffer) {
        AckWireCodec.encode(writeBuffer, AckWire(packetIdentifier.toUShort()))
    }

    override fun expectedResponse(
        reasonCode: ReasonCode,
        reasonString: String?,
        userProperty: List<Pair<String, String>>,
    ) = PublishComplete(packetIdentifier)

    companion object {
        private const val PUBREL_HEADER = 0x6202_0000
        private const val PACKET_ID_MASK = 0x0000_FFFF

        fun from(buffer: ReadBuffer) = PublishRelease(AckWireCodec.decode(buffer).packetId.toInt())
    }
}
