package com.ditchoom.mqtt5.controlpacket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.mqtt.controlpacket.IPublishRelease
import com.ditchoom.mqtt.controlpacket.format.ReasonCode
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.IMPLEMENTATION_SPECIFIC_ERROR
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.NOT_AUTHORIZED
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.PACKET_IDENTIFIER_NOT_FOUND
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.SUCCESS
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.UNSPECIFIED_ERROR
import com.ditchoom.mqtt.controlpacket.format.fixed.DirectionOfFlow

/**
 * 3.6 PUBREL - Publish release (QoS 2 delivery part 2)
 *
 * A PUBREL packet is the response to a PUBREC packet.
 * It is the third packet of the QoS 2 protocol exchange.
 */
data class PublishRelease(
    val variable: AckVariableHeader,
) : ControlPacketV5,
    IPublishRelease {
    override val controlPacketValue: Byte get() = 6
    override val direction: DirectionOfFlow get() = DirectionOfFlow.BIDIRECTIONAL
    override val flags: Byte get() = 0b10

    constructor(
        packetIdentifier: Int,
        reasonCode: ReasonCode = SUCCESS,
        reasonString: String? = null,
        userProperty: List<Pair<String, String>> = emptyList(),
    ) : this(AckVariableHeader(packetIdentifier, reasonCode, AckProperties(reasonString, userProperty)))

    init {
        require(variable.reasonCode.byte in validReasonCodes) {
            "Invalid PUBREL reason code ${variable.reasonCode.byte}"
        }
    }

    override val packetIdentifier: Int = variable.packetIdentifier

    override fun expectedResponse(
        reasonCode: ReasonCode,
        reasonString: String?,
        userProperty: List<Pair<String, String>>,
    ) = PublishComplete(
        AckVariableHeader(
            variable.packetIdentifier,
            reasonCode,
            AckProperties(reasonString, userProperty),
        ),
    )

    override fun encodeBody(writeBuffer: WriteBuffer) = encodeAckBody(writeBuffer, variable)

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
        ) = PublishRelease(
            AckVariableHeader.from(buffer, remainingLength, validReasonCodes, "PUBREL"),
        )
    }
}
