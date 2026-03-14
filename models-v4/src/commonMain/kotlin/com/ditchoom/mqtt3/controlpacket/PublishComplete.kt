package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.mqtt.controlpacket.IPublishComplete
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
    IPublishComplete {
    override val controlPacketValue: Byte get() = IPublishComplete.CONTROL_PACKET_VALUE
    override val direction: DirectionOfFlow get() = DirectionOfFlow.BIDIRECTIONAL

    override fun remainingLength() = 2

    override fun serialize(writeBuffer: WriteBuffer) {
        writeBuffer.writeInt(PUBCOMP_HEADER or (packetIdentifier and PACKET_ID_MASK))
    }

    override fun encodeBody(writeBuffer: WriteBuffer) {
        AckWireCodec.encode(writeBuffer, AckWire(packetIdentifier.toUShort()))
    }

    companion object {
        private const val PUBCOMP_HEADER = 0x7002_0000
        private const val PACKET_ID_MASK = 0x0000_FFFF

        fun from(buffer: ReadBuffer) = PublishComplete(AckWireCodec.decode(buffer).packetId.toInt())
    }
}
