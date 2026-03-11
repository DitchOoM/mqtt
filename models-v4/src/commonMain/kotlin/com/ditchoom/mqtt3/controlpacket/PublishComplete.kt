package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.mqtt.controlpacket.IPublishComplete
import com.ditchoom.mqtt.controlpacket.format.fixed.DirectionOfFlow
import com.ditchoom.mqtt3.controlpacket.wire.AckWire
import com.ditchoom.mqtt3.controlpacket.wire.AckWireCodec

/**
 * 3.7 PUBCOMP – Publish complete (QoS 2 delivery part 3)
 *
 * The PUBCOMP packet is the response to a PUBREL packet. It is the fourth and final packet of the QoS 2 protocol exchange.
 */
data class PublishComplete(
    override val packetIdentifier: Int,
) : ControlPacketV4(IPublishComplete.CONTROL_PACKET_VALUE, DirectionOfFlow.BIDIRECTIONAL),
    IPublishComplete {
    override fun encodeBody(writeBuffer: WriteBuffer) {
        AckWireCodec.encode(writeBuffer, AckWire(packetIdentifier.toUShort()))
    }

    override fun remainingLength() = 2

    companion object {
        fun from(buffer: ReadBuffer) = PublishComplete(AckWireCodec.decode(buffer).packetId.toInt())
    }
}
