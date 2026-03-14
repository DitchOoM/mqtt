package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.mqtt.controlpacket.IPublishAcknowledgment
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
    IPublishAcknowledgment {
    override val controlPacketValue: Byte get() = IPublishAcknowledgment.CONTROL_PACKET_VALUE
    override val direction: DirectionOfFlow get() = DirectionOfFlow.BIDIRECTIONAL

    override fun remainingLength() = 2

    override fun serialize(writeBuffer: WriteBuffer) {
        writeBuffer.writeInt(PUBACK_HEADER or (packetIdentifier and PACKET_ID_MASK))
    }

    override fun encodeBody(writeBuffer: WriteBuffer) {
        AckWireCodec.encode(writeBuffer, AckWire(packetIdentifier.toUShort()))
    }

    companion object {
        private const val PUBACK_HEADER = 0x4002_0000
        private const val PACKET_ID_MASK = 0x0000_FFFF

        fun from(buffer: ReadBuffer) = PublishAcknowledgment(AckWireCodec.decode(buffer).packetId.toInt())
    }
}
