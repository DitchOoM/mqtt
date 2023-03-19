package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.mqtt.MalformedPacketException
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
 * The CONNACK packet is the packet sent by the Server in response to a CONNECT packet received from a Client.
 * The Server MUST send a CONNACK with a 0x00 (Success) Reason Code before sending any Packet other than
 * AUTH [MQTT-3.2.0-1]. The Server MUST NOT send more than one CONNACK in a Network Connection [MQTT-3.2.0-2].
 *
 * If the Client does not receive a CONNACK packet from the Server within a reasonable amount of time, the Client
 * SHOULD close the Network Connection. A "reasonable" amount of time depends on the type of application and the
 * communications infrastructure.
 */
data class ConnectionAcknowledgment(val header: VariableHeader = VariableHeader()) :
    ControlPacketV4(2, DirectionOfFlow.SERVER_TO_CLIENT), IConnectionAcknowledgment {
    constructor(sessionPresent: Boolean, connectReason: ReturnCode) : this(
        VariableHeader(
            sessionPresent,
            connectReason
        )
    )

    override val sessionPresent: Boolean = header.sessionPresent
    override val isSuccessful: Boolean = header.connectReason == CONNECTION_ACCEPTED
    override val connectionReason: String = header.connectReason.name

    override fun variableHeader(writeBuffer: WriteBuffer) = header.serialize(writeBuffer)
    override fun remainingLength() = 2

    /**
     * The Variable Header of the CONNACK Packet contains the following fields in the order: Connect Acknowledge Flags,
     * Connect Reason Code, and Properties. The rules for encoding Properties are described in section 2.2.2.
     *  @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477376">
     *     3.2.2 CONNACK Variable Header</a>
     * @see <a href="https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Properties">
     *     Section 2.2.2</a>
     */
    data class VariableHeader(
        /**
         * 3.2.2.2 Session Present
         *
         * Position: bit 0 of the Connect Acknowledge Flags.
         *
         * If the Server accepts a connection with CleanSession set to 1, the Server MUST set Session Present to 0
         * in the CONNACK packet in addition to setting a zero return code in the CONNACK packet [MQTT-3.2.2-1].
         *
         * If the Server accepts a connection with CleanSession set to 0, the value set in Session Present depends
         * on whether the Server already has stored Session state for the supplied client ID. If the Server has
         * stored Session state, it MUST set Session Present to 1 in the CONNACK packet [MQTT-3.2.2-2]. If the
         * Server does not have stored Session state, it MUST set Session Present to 0 in the CONNACK packet. This
         * is in addition to setting a zero return code in the CONNACK packet [MQTT-3.2.2-3].
         *
         * The Session Present flag enables a Client to establish whether the Client and Server have a consistent
         * view about whether there is already stored Session state.
         *
         * Once the initial setup of a Session is complete, a Client with stored Session state will expect the
         * Server to maintain its stored Session state. In the event that the value of Session Present received
         * by the Client from the Server is not as expected, the Client can choose whether to proceed with the
         * Session or to disconnect. The Client can discard the Session state on both Client and Server by
         * disconnecting, connecting with Clean Session set to 1 and then disconnecting again.
         *
         * If a server sends a CONNACK packet containing a non-zero return code it MUST set Session Present to
         * 0 [MQTT-3.2.2-4].
         */
        val sessionPresent: Boolean = false,
        /**
         * 3.2.2.2 Connect Reason Code
         *
         * Byte 2 in the Variable Header is the Connect Reason Code.
         *
         * The values the Connect Reason Code are shown below. If a well formed CONNECT packet is received by the
         * Server, but the Server is unable to complete the Connection the Server MAY send a CONNACK packet
         * containing the appropriate Connect Reason code from this table. If a Server sends a CONNACK packet
         * containing a Reason code of 128 or greater it MUST then close the Network Connection [MQTT-3.2.2-7].
         * The Server sending the CONNACK packet MUST use one of the Connect Reason Code valuesT-3.2.2-8].
         *
         * Non-normative comment
         *
         * Reason Code 0x80 (Unspecified error) may be used where the Server knows the reason for the failure but
         * does not wish to reveal it to the Client, or when none of the other Reason Code values applies.
         *
         * The Server may choose to close the Network Connection without sending a CONNACK to enhance security
         * in the case where an error is found on the CONNECT. For instance, when on a public network and
         * the connection has not been authorized it might be unwise to indicate that this is an MQTT Server.
         */
        val connectReason: ReturnCode = CONNECTION_ACCEPTED
    ) {

        enum class ReturnCode(val value: UByte) {
            CONNECTION_ACCEPTED(0.toUByte()),
            CONNECTION_REFUSED_UNACCEPTABLE_PROTOCOL_VERSION(1.toUByte()),
            CONNECTION_REFUSED_IDENTIFIER_REJECTED(2.toUByte()),
            CONNECTION_REFUSED_SERVER_UNAVAILABLE(3.toUByte()),
            CONNECTION_REFUSED_BAD_USER_NAME_OR_PASSWORD(4.toUByte()),
            CONNECTION_REFUSED_NOT_AUTHORIZED(5.toUByte()),
            RESERVED(6.toUByte())
        }

        fun serialize(writeBuffer: WriteBuffer) {
            writeBuffer.writeByte((if (sessionPresent) 0b1 else 0b0).toByte())
            writeBuffer.writeUByte(connectReason.value)
        }

        companion object {
            fun from(buffer: ReadBuffer): VariableHeader {
                val sessionPresent = buffer.readByte() == 1.toByte()
                val connectionReasonByte = buffer.readUnsignedByte()
                val connectionReasonByteNormalized = if (connectionReasonByte > 5.toUByte()) {
                    RESERVED
                } else {
                    connectionReasonByte
                }
                val connectionReason = connackReturnCode[connectionReasonByteNormalized]
                if (connectionReason == null) {
                    throw MalformedPacketException("Invalid property type found in MQTT payload $connectionReason")
                }
                return VariableHeader(sessionPresent, connectionReason)
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
        Pair(
            CONNECTION_REFUSED_UNACCEPTABLE_PROTOCOL_VERSION.value,
            CONNECTION_REFUSED_UNACCEPTABLE_PROTOCOL_VERSION
        ),
        Pair(CONNECTION_REFUSED_IDENTIFIER_REJECTED.value, CONNECTION_REFUSED_IDENTIFIER_REJECTED),
        Pair(CONNECTION_REFUSED_SERVER_UNAVAILABLE.value, CONNECTION_REFUSED_SERVER_UNAVAILABLE),
        Pair(
            CONNECTION_REFUSED_BAD_USER_NAME_OR_PASSWORD.value,
            CONNECTION_REFUSED_BAD_USER_NAME_OR_PASSWORD
        ),
        Pair(CONNECTION_REFUSED_NOT_AUTHORIZED.value, CONNECTION_REFUSED_NOT_AUTHORIZED),
        Pair(RESERVED.value, CONNECTION_REFUSED_NOT_AUTHORIZED)
    )
}
