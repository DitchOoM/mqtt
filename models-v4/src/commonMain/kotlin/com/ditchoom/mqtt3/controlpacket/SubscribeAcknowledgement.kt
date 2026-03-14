package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.mqtt.MalformedPacketException
import com.ditchoom.mqtt.controlpacket.ISubscribeAcknowledgement
import com.ditchoom.mqtt.controlpacket.format.ReasonCode
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.GRANTED_QOS_0
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.GRANTED_QOS_1
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.GRANTED_QOS_2
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.UNSPECIFIED_ERROR
import com.ditchoom.mqtt.controlpacket.format.fixed.DirectionOfFlow
import com.ditchoom.mqtt3.controlpacket.wire.SubAckReturnCodeWire
import com.ditchoom.mqtt3.controlpacket.wire.SubAckWire
import com.ditchoom.mqtt3.controlpacket.wire.SubAckWireCodec

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
) : ControlPacketV4,
    ISubscribeAcknowledgement {
    override val controlPacketValue: Byte get() = ISubscribeAcknowledgement.CONTROL_PACKET_VALUE
    override val direction: DirectionOfFlow get() = DirectionOfFlow.SERVER_TO_CLIENT
    override fun remainingLength() = 2 + payload.size

    override fun encodeBody(writeBuffer: WriteBuffer) {
        SubAckWireCodec.encode(
            writeBuffer,
            SubAckWire(
                packetIdentifier.toUShort(),
                payload.map { SubAckReturnCodeWire(it.byte) },
            ),
        )
    }

    companion object {
        fun from(
            buffer: ReadBuffer,
            remainingLength: Int,
        ): SubscribeAcknowledgement {
            val sliced = buffer.readBytes(remainingLength)
            val wire = SubAckWireCodec.decode(sliced)
            val returnCodes = wire.returnCodes.map { rc ->
                when (rc.raw) {
                    GRANTED_QOS_0.byte -> GRANTED_QOS_0
                    GRANTED_QOS_1.byte -> GRANTED_QOS_1
                    GRANTED_QOS_2.byte -> GRANTED_QOS_2
                    UNSPECIFIED_ERROR.byte -> UNSPECIFIED_ERROR
                    else -> throw MalformedPacketException("Invalid return code ${rc.raw}")
                }
            }
            return SubscribeAcknowledgement(wire.packetIdentifier.toInt(), returnCodes)
        }
    }
}
