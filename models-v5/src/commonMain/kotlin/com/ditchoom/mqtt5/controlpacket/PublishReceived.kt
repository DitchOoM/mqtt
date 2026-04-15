package com.ditchoom.mqtt5.controlpacket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.mqtt.controlpacket.IPublishReceived
import com.ditchoom.mqtt.controlpacket.format.ReasonCode
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.IMPLEMENTATION_SPECIFIC_ERROR
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.NOT_AUTHORIZED
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.NO_MATCHING_SUBSCRIBERS
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.PACKET_IDENTIFIER_IN_USE
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.PAYLOAD_FORMAT_INVALID
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.QUOTA_EXCEEDED
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.SUCCESS
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.TOPIC_NAME_INVALID
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.UNSPECIFIED_ERROR
import com.ditchoom.mqtt.controlpacket.format.fixed.DirectionOfFlow

/**
 * 3.5 PUBREC - Publish received (QoS 2 delivery part 1)
 *
 * A PUBREC packet is the response to a PUBLISH packet with QoS 2.
 * It is the second packet of the QoS 2 protocol exchange.
 */
data class PublishReceived(
    val variable: AckVariableHeader,
) : ControlPacketV5,
    IPublishReceived {
    override val controlPacketValue: Byte get() = 5
    override val direction: DirectionOfFlow get() = DirectionOfFlow.BIDIRECTIONAL

    constructor(
        packetIdentifier: Int,
        reasonCode: ReasonCode = SUCCESS,
        reasonString: String? = null,
        userProperty: List<Pair<String, String>> = emptyList(),
    ) : this(AckVariableHeader(packetIdentifier, reasonCode, AckProperties(reasonString, userProperty)))

    init {
        require(variable.reasonCode.byte in validReasonCodes) {
            "Invalid PUBREC reason code ${variable.reasonCode.byte}"
        }
    }

    override fun expectedResponse(
        reasonCode: ReasonCode,
        reasonString: String?,
        userProperty: List<Pair<String, String>>,
    ) = PublishRelease(
        AckVariableHeader(
            variable.packetIdentifier,
            reasonCode,
            AckProperties(reasonString, userProperty),
        ),
    )

    override val packetIdentifier: Int = variable.packetIdentifier

    override fun encodeBody(writeBuffer: WriteBuffer) = encodeAckBody(writeBuffer, variable)

    override fun remainingLength() = variable.size()

    companion object {
        private val validReasonCodes =
            mapOf(
                SUCCESS.byte to SUCCESS,
                NO_MATCHING_SUBSCRIBERS.byte to NO_MATCHING_SUBSCRIBERS,
                UNSPECIFIED_ERROR.byte to UNSPECIFIED_ERROR,
                IMPLEMENTATION_SPECIFIC_ERROR.byte to IMPLEMENTATION_SPECIFIC_ERROR,
                NOT_AUTHORIZED.byte to NOT_AUTHORIZED,
                TOPIC_NAME_INVALID.byte to TOPIC_NAME_INVALID,
                PACKET_IDENTIFIER_IN_USE.byte to PACKET_IDENTIFIER_IN_USE,
                QUOTA_EXCEEDED.byte to QUOTA_EXCEEDED,
                PAYLOAD_FORMAT_INVALID.byte to PAYLOAD_FORMAT_INVALID,
            )

        fun from(
            buffer: ReadBuffer,
            remainingLength: Int,
        ) = PublishReceived(
            AckVariableHeader.from(buffer, remainingLength, validReasonCodes, "PUBREC"),
        )
    }
}
