package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.mqtt.MalformedPacketException
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.variableByteSize
import com.ditchoom.mqtt.controlpacket.ISubscribeAcknowledgement
import com.ditchoom.mqtt.controlpacket.format.ReasonCode
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.GRANTED_QOS_0
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.GRANTED_QOS_1
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.GRANTED_QOS_2
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.UNSPECIFIED_ERROR
import com.ditchoom.mqtt.controlpacket.format.fixed.DirectionOfFlow

/**
 * 3.9 SUBACK – Subscribe acknowledgement
 *
 * A SUBACK Packet is sent by the Server to the Client to confirm receipt and processing of a SUBSCRIBE Packet.
 *
 * A SUBACK Packet contains a list of return codes, that specify the maximum QoS level that was granted in each
 * Subscription that was requested by the SUBSCRIBE.
 */
data class SubscribeAcknowledgement(
    override val packetIdentifier: Int,
    val payload: List<ReasonCode>,
) :
    ControlPacketV4(ISubscribeAcknowledgement.CONTROL_PACKET_VALUE, DirectionOfFlow.SERVER_TO_CLIENT),
        ISubscribeAcknowledgement {
    override fun remainingLength() = 2 + payload.size

    override fun variableHeader(writeBuffer: WriteBuffer) {
        writeBuffer.writeUShort(packetIdentifier.toUShort())
    }

    override fun payload(writeBuffer: WriteBuffer) {
        payload.forEach { writeBuffer.writeUByte(it.byte) }
    }

    companion object {
        fun from(
            buffer: ReadBuffer,
            remainingLength: Int,
        ): SubscribeAcknowledgement {
            val packetIdentifier = buffer.readUnsignedShort()
            val returnCodes = mutableListOf<ReasonCode>()
            while (returnCodes.size < remainingLength - variableByteSize(remainingLength) - 1) {
                val reasonCode =
                    when (val reasonCodeByte = buffer.readUnsignedByte()) {
                        GRANTED_QOS_0.byte -> GRANTED_QOS_0
                        GRANTED_QOS_1.byte -> GRANTED_QOS_1
                        GRANTED_QOS_2.byte -> GRANTED_QOS_2
                        UNSPECIFIED_ERROR.byte -> UNSPECIFIED_ERROR
                        else -> throw MalformedPacketException("Invalid return code $reasonCodeByte")
                    }
                returnCodes += reasonCode
            }
            return SubscribeAcknowledgement(packetIdentifier.toInt(), returnCodes)
        }
    }
}
