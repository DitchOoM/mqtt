package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.mqtt.controlpacket.IUnsubscribeAcknowledgment
import com.ditchoom.mqtt.controlpacket.format.fixed.DirectionOfFlow
import com.ditchoom.mqtt3.controlpacket.wire.AckWire
import com.ditchoom.mqtt3.controlpacket.wire.AckWireCodec
import kotlin.jvm.JvmInline

@JvmInline
value class UnsubscribeAcknowledgment(
    override val packetIdentifier: Int,
) : ControlPacketV4,
    IUnsubscribeAcknowledgment {
    override val controlPacketValue: Byte get() = IUnsubscribeAcknowledgment.CONTROL_PACKET_VALUE
    override val direction: DirectionOfFlow get() = DirectionOfFlow.SERVER_TO_CLIENT

    override fun remainingLength() = 2

    override fun serialize(writeBuffer: WriteBuffer) {
        writeBuffer.writeInt(UNSUBACK_HEADER or (packetIdentifier and PACKET_ID_MASK))
    }

    override fun encodeBody(writeBuffer: WriteBuffer) {
        AckWireCodec.encode(writeBuffer, AckWire(packetIdentifier.toUShort()))
    }

    companion object {
        private val UNSUBACK_HEADER = 0xB002_0000.toInt()
        private const val PACKET_ID_MASK = 0x0000_FFFF

        fun from(buffer: ReadBuffer) = UnsubscribeAcknowledgment(AckWireCodec.decode(buffer).packetId.toInt())
    }
}
