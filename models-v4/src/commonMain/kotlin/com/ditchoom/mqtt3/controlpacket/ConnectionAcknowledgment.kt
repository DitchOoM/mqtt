package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.mqtt.controlpacket.IConnectionAcknowledgment
import com.ditchoom.mqtt.controlpacket.format.fixed.DirectionOfFlow
import com.ditchoom.mqtt3.controlpacket.ConnectionAcknowledgment.VariableHeader.ReturnCode
import com.ditchoom.mqtt3.controlpacket.ConnectionAcknowledgment.VariableHeader.ReturnCode.CONNECTION_ACCEPTED
import com.ditchoom.mqtt3.controlpacket.ConnectionAcknowledgment.VariableHeader.ReturnCode.CONNECTION_REFUSED_BAD_USER_NAME_OR_PASSWORD
import com.ditchoom.mqtt3.controlpacket.ConnectionAcknowledgment.VariableHeader.ReturnCode.CONNECTION_REFUSED_IDENTIFIER_REJECTED
import com.ditchoom.mqtt3.controlpacket.ConnectionAcknowledgment.VariableHeader.ReturnCode.CONNECTION_REFUSED_NOT_AUTHORIZED
import com.ditchoom.mqtt3.controlpacket.ConnectionAcknowledgment.VariableHeader.ReturnCode.CONNECTION_REFUSED_SERVER_UNAVAILABLE
import com.ditchoom.mqtt3.controlpacket.ConnectionAcknowledgment.VariableHeader.ReturnCode.CONNECTION_REFUSED_UNACCEPTABLE_PROTOCOL_VERSION
import com.ditchoom.mqtt3.controlpacket.ConnectionAcknowledgment.VariableHeader.ReturnCode.RESERVED

typealias CONNACK = ConnectionAcknowledgment

/**
 * Wire model for CONNACK: 1 byte acknowledge flags + 1 byte return code.
 */
@ProtocolMessage
data class ConnAckBody(val acknowledgeFlags: UByte, val returnCode: UByte)

/**
 * The CONNACK packet is the packet sent by the Server in response to a CONNECT packet received from a Client.
 */
data class ConnectionAcknowledgment(
    val header: VariableHeader = VariableHeader(),
) : ControlPacketV4,
    IConnectionAcknowledgment {
    override val controlPacketValue: Byte get() = 2
    override val direction: DirectionOfFlow get() = DirectionOfFlow.SERVER_TO_CLIENT

    constructor(sessionPresent: Boolean, connectReason: ReturnCode) : this(
        VariableHeader(sessionPresent, connectReason),
    )

    override val sessionPresent: Boolean = header.sessionPresent
    override val isSuccessful: Boolean = header.connectReason == CONNECTION_ACCEPTED
    override val connectionReason: String = header.connectReason.name

    override fun encodeBody(writeBuffer: WriteBuffer) {
        ConnAckBodyCodec.encode(
            writeBuffer,
            ConnAckBody(
                (if (header.sessionPresent) 1u else 0u).toUByte(),
                header.connectReason.value,
            ),
        )
    }

    override fun remainingLength() = 2

    data class VariableHeader(
        val sessionPresent: Boolean = false,
        val connectReason: ReturnCode = CONNECTION_ACCEPTED,
    ) {
        enum class ReturnCode(val value: UByte) {
            CONNECTION_ACCEPTED(0.toUByte()),
            CONNECTION_REFUSED_UNACCEPTABLE_PROTOCOL_VERSION(1.toUByte()),
            CONNECTION_REFUSED_IDENTIFIER_REJECTED(2.toUByte()),
            CONNECTION_REFUSED_SERVER_UNAVAILABLE(3.toUByte()),
            CONNECTION_REFUSED_BAD_USER_NAME_OR_PASSWORD(4.toUByte()),
            CONNECTION_REFUSED_NOT_AUTHORIZED(5.toUByte()),
            RESERVED(6.toUByte()),
        }

        companion object {
            fun from(buffer: ReadBuffer): VariableHeader {
                val wire = ConnAckBodyCodec.decode(buffer)
                val sessionPresent = wire.acknowledgeFlags.toInt() and 1 == 1
                val returnCodeByte = wire.returnCode
                val returnCodeNormalized = if (returnCodeByte > 5.toUByte()) RESERVED else returnCodeByte
                val connectReason = connackReturnCode[returnCodeNormalized]
                    ?: throw com.ditchoom.mqtt.MalformedPacketException(
                        "Invalid property type found in MQTT payload $returnCodeNormalized",
                    )
                return VariableHeader(sessionPresent, connectReason)
            }
        }
    }

    companion object {
        fun from(buffer: ReadBuffer) = ConnectionAcknowledgment(VariableHeader.from(buffer))
    }
}

val connackReturnCode by lazy(LazyThreadSafetyMode.NONE) {
    mapOf(
        Pair(CONNECTION_ACCEPTED.value, CONNECTION_ACCEPTED),
        Pair(CONNECTION_REFUSED_UNACCEPTABLE_PROTOCOL_VERSION.value, CONNECTION_REFUSED_UNACCEPTABLE_PROTOCOL_VERSION),
        Pair(CONNECTION_REFUSED_IDENTIFIER_REJECTED.value, CONNECTION_REFUSED_IDENTIFIER_REJECTED),
        Pair(CONNECTION_REFUSED_SERVER_UNAVAILABLE.value, CONNECTION_REFUSED_SERVER_UNAVAILABLE),
        Pair(CONNECTION_REFUSED_BAD_USER_NAME_OR_PASSWORD.value, CONNECTION_REFUSED_BAD_USER_NAME_OR_PASSWORD),
        Pair(CONNECTION_REFUSED_NOT_AUTHORIZED.value, CONNECTION_REFUSED_NOT_AUTHORIZED),
        Pair(RESERVED.value, CONNECTION_REFUSED_NOT_AUTHORIZED),
    )
}
