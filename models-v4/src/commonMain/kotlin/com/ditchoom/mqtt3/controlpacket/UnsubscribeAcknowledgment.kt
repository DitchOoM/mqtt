package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.mqtt.controlpacket.IUnsubscribeAcknowledgment
import com.ditchoom.mqtt.controlpacket.format.fixed.DirectionOfFlow

data class UnsubscribeAcknowledgment(override val packetIdentifier: Int) :
    ControlPacketV4(IUnsubscribeAcknowledgment.controlPacketValue, DirectionOfFlow.SERVER_TO_CLIENT),
    IUnsubscribeAcknowledgment {
    override fun remainingLength() = 2

    override fun variableHeader(writeBuffer: WriteBuffer) {
        writeBuffer.writeUShort(packetIdentifier.toUShort())
    }

    companion object {
        fun from(buffer: ReadBuffer) = UnsubscribeAcknowledgment(buffer.readUnsignedShort().toInt())
    }
}
