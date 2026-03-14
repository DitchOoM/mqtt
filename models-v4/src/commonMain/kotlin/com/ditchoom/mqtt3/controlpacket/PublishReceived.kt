package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.mqtt.controlpacket.IPublishReceived
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
    IPublishReceived {
    override val controlPacketValue: Byte get() = IPublishReceived.CONTROL_PACKET_VALUE
    override val direction: DirectionOfFlow get() = DirectionOfFlow.BIDIRECTIONAL

    override fun remainingLength() = 2

    override fun serialize(writeBuffer: WriteBuffer) {
        writeBuffer.writeInt(PUBREC_HEADER or (packetIdentifier and PACKET_ID_MASK))
    }

    override fun encodeBody(writeBuffer: WriteBuffer) {
        AckWireCodec.encode(writeBuffer, AckWire(packetIdentifier.toUShort()))
    }

    override fun expectedResponse(
        reasonCode: ReasonCode,
        reasonString: String?,
        userProperty: List<Pair<String, String>>,
    ) = PublishRelease(packetIdentifier.toUShort().toInt())

    companion object {
        private const val PUBREC_HEADER = 0x5002_0000
        private const val PACKET_ID_MASK = 0x0000_FFFF

        fun from(buffer: ReadBuffer) = PublishReceived(AckWireCodec.decode(buffer).packetId.toInt())
    }
}
