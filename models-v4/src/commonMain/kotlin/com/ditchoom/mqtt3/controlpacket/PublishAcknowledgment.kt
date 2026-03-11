package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.mqtt.controlpacket.IPublishAcknowledgment
import com.ditchoom.mqtt.controlpacket.format.fixed.DirectionOfFlow
import com.ditchoom.mqtt3.controlpacket.wire.AckWire
import com.ditchoom.mqtt3.controlpacket.wire.AckWireCodec

/**
 * 3.4 PUBACK – Publish acknowledgement
 *
 * A PUBACK packet is the response to a PUBLISH packet with QoS 1.
 */
data class PublishAcknowledgment(
    override val packetIdentifier: Int,
) : ControlPacketV4(IPublishAcknowledgment.CONTROL_PACKET_VALUE, DirectionOfFlow.BIDIRECTIONAL),
    IPublishAcknowledgment {
    override fun remainingLength() = 2

    override fun encodeBody(writeBuffer: WriteBuffer) {
        AckWireCodec.encode(writeBuffer, AckWire(packetIdentifier.toUShort()))
    }

    companion object {
        fun from(buffer: ReadBuffer) = PublishAcknowledgment(AckWireCodec.decode(buffer).packetId.toInt())
    }
}
