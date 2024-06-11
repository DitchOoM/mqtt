package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.mqtt.controlpacket.IPublishComplete
import com.ditchoom.mqtt.controlpacket.format.fixed.DirectionOfFlow

/**
 * 3.7 PUBCOMP – Publish complete (QoS 2 delivery part 3)
 *
 * The PUBCOMP packet is the response to a PUBREL packet. It is the fourth and final packet of the QoS 2 protocol exchange.
 */
data class PublishComplete(override val packetIdentifier: Int) :
    ControlPacketV4(IPublishComplete.CONTROL_PACKET_VALUE, DirectionOfFlow.BIDIRECTIONAL),
    IPublishComplete {
    override fun variableHeader(writeBuffer: WriteBuffer) {
        writeBuffer.writeUShort(packetIdentifier.toUShort())
    }

    override fun remainingLength() = 2

    companion object {
        fun from(buffer: ReadBuffer) = PublishComplete(buffer.readUnsignedShort().toInt())
    }
}
