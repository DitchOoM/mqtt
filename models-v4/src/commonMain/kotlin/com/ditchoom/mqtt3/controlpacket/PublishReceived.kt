package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.mqtt.controlpacket.IPublishReceived
import com.ditchoom.mqtt.controlpacket.format.ReasonCode
import com.ditchoom.mqtt.controlpacket.format.fixed.DirectionOfFlow

/**
 * 3.5 PUBREC – Publish received (QoS 2 delivery part 1)
 *
 * A PUBREC packet is the response to a PUBLISH packet with QoS 2. It is the second packet of the QoS 2 protocol exchange.
 */
data class PublishReceived(override val packetIdentifier: Int) :
    ControlPacketV4(IPublishReceived.CONTROL_PACKET_VALUE, DirectionOfFlow.BIDIRECTIONAL),
    IPublishReceived {
    override fun variableHeader(writeBuffer: WriteBuffer) {
        writeBuffer.writeUShort(packetIdentifier.toUShort())
    }

    override fun remainingLength() = 2

    override fun expectedResponse(
        reasonCode: ReasonCode,
        reasonString: String?,
        userProperty: List<Pair<String, String>>,
    ) = PublishRelease(packetIdentifier.toUShort().toInt())

    companion object {
        fun from(buffer: ReadBuffer) = PublishReceived(buffer.readUnsignedShort().toInt())
    }
}
