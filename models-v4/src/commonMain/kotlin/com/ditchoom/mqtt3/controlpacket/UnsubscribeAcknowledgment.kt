package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.mqtt.controlpacket.IUnsubscribeAcknowledgment
import com.ditchoom.mqtt.controlpacket.format.fixed.DirectionOfFlow
import com.ditchoom.mqtt3.controlpacket.wire.AckWire
import com.ditchoom.mqtt3.controlpacket.wire.AckWireCodec

data class UnsubscribeAcknowledgment(
    override val packetIdentifier: Int,
) : ControlPacketV4(IUnsubscribeAcknowledgment.CONTROL_PACKET_VALUE, DirectionOfFlow.SERVER_TO_CLIENT),
    IUnsubscribeAcknowledgment {
    override fun remainingLength() = 2

    override fun encodeBody(writeBuffer: WriteBuffer) {
        AckWireCodec.encode(writeBuffer, AckWire(packetIdentifier.toUShort()))
    }

    companion object {
        fun from(buffer: ReadBuffer) = UnsubscribeAcknowledgment(AckWireCodec.decode(buffer).packetId.toInt())
    }
}
