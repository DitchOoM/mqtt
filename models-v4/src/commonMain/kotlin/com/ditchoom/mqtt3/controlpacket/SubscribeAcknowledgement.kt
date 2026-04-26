package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes
import com.ditchoom.mqtt.MalformedPacketException
import com.ditchoom.mqtt.controlpacket.ISubscribeAcknowledgement
import com.ditchoom.mqtt.controlpacket.format.ReasonCode
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.GRANTED_QOS_0
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.GRANTED_QOS_1
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.GRANTED_QOS_2
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.UNSPECIFIED_ERROR
import com.ditchoom.mqtt.controlpacket.format.fixed.DirectionOfFlow
import kotlin.jvm.JvmInline

/**
 * Wire model for a single SUBACK return code byte.
 */
@ProtocolMessage
@JvmInline
value class SubAckReturnCode(
    val raw: UByte,
)

/**
 * 3.9 SUBACK – Subscribe acknowledgement
 *
 * A SUBACK Packet is sent by the Server to the Client to confirm receipt and processing of a SUBSCRIBE Packet.
 *
 * A SUBACK Packet contains a list of return codes, that specify the maximum QoS level that was granted in each
 * Subscription that was requested by the SUBSCRIBE.
 */
@ProtocolMessage
data class SubscribeAcknowledgement(
    val packetId: UShort,
    @RemainingBytes val returnCodes: List<SubAckReturnCode>,
) : ControlPacketV4,
    ISubscribeAcknowledgement {
    override val packetIdentifier: Int get() = packetId.toInt()
    override val controlPacketValue: Byte get() = ISubscribeAcknowledgement.CONTROL_PACKET_VALUE
    override val direction: DirectionOfFlow get() = DirectionOfFlow.SERVER_TO_CLIENT

    override fun remainingLength() = SubscribeAcknowledgementCodec.wireSize(this)

    /**
     * Convenience constructor from domain-level [ReasonCode] list.
     */
    constructor(packetIdentifier: Int, payload: List<ReasonCode>) :
        this(packetIdentifier.toUShort(), payload.map { SubAckReturnCode(it.byte) })

    val payload: List<ReasonCode>
        get() =
            returnCodes.map { rc ->
                when (rc.raw) {
                    GRANTED_QOS_0.byte -> GRANTED_QOS_0
                    GRANTED_QOS_1.byte -> GRANTED_QOS_1
                    GRANTED_QOS_2.byte -> GRANTED_QOS_2
                    UNSPECIFIED_ERROR.byte -> UNSPECIFIED_ERROR
                    else -> throw MalformedPacketException("Invalid return code ${rc.raw}")
                }
            }

    override fun encodeBody(writeBuffer: WriteBuffer) = SubscribeAcknowledgementCodec.encode(writeBuffer, this)

    companion object {
        fun from(
            buffer: ReadBuffer,
            remainingLength: Int,
        ): SubscribeAcknowledgement {
            val sliced = buffer.readBytes(remainingLength)
            return SubscribeAcknowledgementCodec.decode(sliced)
        }
    }
}
