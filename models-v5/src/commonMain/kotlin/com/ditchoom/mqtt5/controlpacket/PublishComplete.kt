package com.ditchoom.mqtt5.controlpacket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.mqtt.controlpacket.IPublishComplete
import com.ditchoom.mqtt.controlpacket.format.ReasonCode
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.IMPLEMENTATION_SPECIFIC_ERROR
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.NOT_AUTHORIZED
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.PACKET_IDENTIFIER_NOT_FOUND
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.SUCCESS
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.UNSPECIFIED_ERROR
import com.ditchoom.mqtt.controlpacket.format.fixed.DirectionOfFlow

/**
 * 3.7 PUBCOMP - Publish complete (QoS 2 delivery part 3)
 *
 * The PUBCOMP packet is the response to a PUBREL packet.
 * It is the fourth and final packet of the QoS 2 protocol exchange.
 */
data class PublishComplete(
    val variable: AckVariableHeader,
) : ControlPacketV5,
    IPublishComplete {
    override val controlPacketValue: Byte get() = 7
    override val direction: DirectionOfFlow get() = DirectionOfFlow.BIDIRECTIONAL

    constructor(packetIdentifier: UShort, reasonCode: ReasonCode = SUCCESS) :
        this(AckVariableHeader(packetIdentifier.toInt(), reasonCode))

    constructor(
        packetIdentifier: Int,
        reasonCode: ReasonCode = SUCCESS,
        reasonString: String? = null,
        userProperty: List<Pair<String, String>> = emptyList(),
    ) : this(AckVariableHeader(packetIdentifier, reasonCode, AckProperties(reasonString, userProperty)))

    init {
        require(variable.reasonCode.byte in validReasonCodes) {
            "Invalid PUBCOMP reason code ${variable.reasonCode.byte}"
        }
    }

    override fun encodeBody(writeBuffer: WriteBuffer) = encodeAckBody(writeBuffer, variable)

    override val packetIdentifier = variable.packetIdentifier

    override fun remainingLength() = variable.size()

    companion object {
        private val validReasonCodes =
            mapOf(
                SUCCESS.byte to SUCCESS,
                PACKET_IDENTIFIER_NOT_FOUND.byte to PACKET_IDENTIFIER_NOT_FOUND,
                UNSPECIFIED_ERROR.byte to UNSPECIFIED_ERROR,
                IMPLEMENTATION_SPECIFIC_ERROR.byte to IMPLEMENTATION_SPECIFIC_ERROR,
                NOT_AUTHORIZED.byte to NOT_AUTHORIZED,
            )

        fun from(
            buffer: ReadBuffer,
            remainingLength: Int,
        ) = PublishComplete(
            AckVariableHeader.from(buffer, remainingLength, validReasonCodes, "PUBCOMP"),
        )
    }
}
