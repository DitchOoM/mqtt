package com.ditchoom.mqtt5.controlpacket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.WhenRemaining
import com.ditchoom.mqtt.MalformedPacketException
import com.ditchoom.mqtt.codec.annotations.MqttProperties
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.variableByteSize
import com.ditchoom.mqtt.controlpacket.IDisconnectNotification
import com.ditchoom.mqtt.controlpacket.format.ReasonCode
import com.ditchoom.mqtt.controlpacket.format.fixed.DirectionOfFlow
import com.ditchoom.mqtt5.controlpacket.properties.MqttProperty
import com.ditchoom.mqtt5.controlpacket.properties.PropertyExtractor
import com.ditchoom.mqtt5.controlpacket.properties.ReasonString
import com.ditchoom.mqtt5.controlpacket.properties.ServerReference
import com.ditchoom.mqtt5.controlpacket.properties.SessionExpiryInterval
import com.ditchoom.mqtt5.controlpacket.properties.UserProperty
import com.ditchoom.mqtt5.controlpacket.properties.mqttPropertiesSize

/**
 * 3.14 DISCONNECT – Disconnect notification
 *
 * The DISCONNECT packet is the final MQTT Control Packet sent from the Client or the Server. It indicates the reason
 * why the Network Connection is being closed. The Client or Server MAY send a DISCONNECT packet before closing the
 * Network Connection. If the Network Connection is closed without the Client first sending a DISCONNECT packet with
 * Reason Code 0x00 (Normal disconnection) and the Connection has a Will Message, the Will Message is published. Refer
 * to section 3.1.2.5 for further details.
 *
 * A Server MUST NOT send a DISCONNECT until after it has sent a CONNACK with Reason Code of less than 0x80
 * [MQTT-3.14.0-1].
 */

/**
 * Wire body for DISCONNECT: reasonCode + optional properties.
 * Both fields optional when Remaining Length is 0 (NORMAL_DISCONNECTION with no properties).
 */
@ProtocolMessage
data class DisconnectV5Body(
    @WhenRemaining(1) val reasonCode: UByte? = null,
    @WhenRemaining(1) @MqttProperties val properties: Collection<MqttProperty>? = null,
)

data class DisconnectNotification(
    val variable: VariableHeader = VariableHeader(),
) : ControlPacketV5,
    IDisconnectNotification {
    override val controlPacketValue: Byte get() = 14
    override val direction: DirectionOfFlow get() = DirectionOfFlow.BIDIRECTIONAL

    override fun encodeBody(writeBuffer: WriteBuffer) {
        val canOmit =
            variable.reasonCode == ReasonCode.NORMAL_DISCONNECTION &&
                variable.properties.props.isEmpty()
        if (!canOmit) {
            DisconnectV5BodyCodec.encode(
                writeBuffer,
                DisconnectV5Body(variable.reasonCode.byte, variable.properties.props),
            )
        }
    }

    override fun remainingLength(): Int {
        val canOmit =
            variable.reasonCode == ReasonCode.NORMAL_DISCONNECTION &&
                variable.properties.props.isEmpty()
        if (canOmit) return 0
        val propsSize = mqttPropertiesSize(variable.properties.props)
        return 1 + variableByteSize(propsSize) + propsSize
    }

    data class VariableHeader(
        val reasonCode: ReasonCode = ReasonCode.NORMAL_DISCONNECTION,
        val properties: Properties = Properties(),
    ) {
        init {
            // throw if the reason code is not valid for the disconnect notification
            getDisconnectCode(reasonCode.byte)
        }

        data class Properties(
            /**
             * 3.14.2.2.2 Session Expiry Interval
             *
             * 17 (0x11) Byte, Identifier of the Session Expiry Interval.
             *
             * Followed by the Four Byte Integer representing the Session Expiry Interval in seconds. It is a
             * Protocol Error to include the Session Expiry Interval more than once.
             *
             * If the Session Expiry Interval is absent, the Session Expiry Interval in the CONNECT packet is used.
             *
             * The Session Expiry Interval MUST NOT be sent on a DISCONNECT by the Server [MQTT-3.14.2-2].
             *
             * If the Session Expiry Interval in the CONNECT packet was zero, then it is a Protocol Error to set a
             * non-zero Session Expiry Interval in the DISCONNECT packet sent by the Client. If such a non-zero
             * Session Expiry Interval is received by the Server, it does not treat it as a valid DISCONNECT
             * packet. The Server uses DISCONNECT with Reason Code 0x82 (Protocol Error) as described in
             * section 4.13.
             */
            val sessionExpiryIntervalSeconds: ULong? = null,
            /**
             * 3.14.2.2.3 Reason String
             *
             * 31 (0x1F) Byte, Identifier of the Reason String.
             *
             * Followed by the UTF-8 Encoded String representing the reason for the disconnect. This Reason
             * String is human readable, designed for diagnostics and SHOULD NOT be parsed by the receiver.
             *
             * The sender MUST NOT send this Property if it would increase the size of the DISCONNECT packet
             * beyond the Maximum Packet Size specified by the receiver [MQTT-3.14.2-3]. It is a Protocol Error
             * to include the Reason String more than once.
             */
            val reasonString: String? = null,
            /**
             * 3.14.2.2.4 User Property
             *
             * 38 (0x26) Byte, Identifier of the User Property.
             *
             * Followed by UTF-8 String Pair. This property may be used to provide additional diagnostic or other
             * information. The sender MUST NOT send this property if it would increase the size of the DISCONNECT
             * packet beyond the Maximum Packet Size specified by the receiver [MQTT-3.14.2-4]. The User Property
             * is allowed to appear multiple times to represent multiple name, value pairs. The same name is
             * allowed to appear more than once.
             */
            val userProperty: List<Pair<String, String>> = emptyList(),
            /**
             * 3.14.2.2.5 Server Reference
             *
             * 28 (0x1C) Byte, Identifier of the Server Reference.
             *
             * Followed by a UTF-8 Encoded String which can be used by the Client to identify another Server to
             * use. It is a Protocol Error to include the Server Reference more than once.
             *
             * The Server sends DISCONNECT including a Server Reference and Reason Code 0x9C (Use another server)
             * or 0x9D (Server moved) as described in section 4.13.
             *
             * Refer to section 4.11 Server Redirection for information about how Server Reference is used.
             */
            val serverReference: String? = null,
        ) {
            val props: List<MqttProperty> =
                buildList {
                    if (sessionExpiryIntervalSeconds != null) {
                        add(SessionExpiryInterval(sessionExpiryIntervalSeconds.toUInt()))
                    }
                    if (reasonString != null) {
                        add(ReasonString(reasonString))
                    }
                    if (userProperty.isNotEmpty()) {
                        for (keyValueProperty in userProperty) {
                            val key = keyValueProperty.first
                            val value = keyValueProperty.second
                            add(UserProperty(key, value))
                        }
                    }
                    if (serverReference != null) {
                        add(ServerReference(serverReference))
                    }
                }

            companion object {
                fun from(keyValuePairs: Collection<MqttProperty>?): Properties {
                    val p = PropertyExtractor(keyValuePairs, "DISCONNECT")
                    val sessionExpiryIntervalSeconds = p.single<SessionExpiryInterval>()?.seconds?.toULong()
                    val reasonString = p.single<ReasonString>()?.value
                    val userProperty = p.list<UserProperty>().map { it.key to it.value }
                    val serverReference = p.single<ServerReference>()?.value
                    p.rejectUnknown()
                    return Properties(
                        sessionExpiryIntervalSeconds,
                        reasonString,
                        userProperty,
                        serverReference,
                    )
                }
            }
        }

        companion object {
            fun from(
                buffer: ReadBuffer,
                remainingLength: Int,
            ): VariableHeader {
                if (remainingLength == 0) {
                    return VariableHeader(ReasonCode.NORMAL_DISCONNECTION)
                }
                val body =
                    if (remainingLength == 1) {
                        DisconnectV5Body(buffer.readUnsignedByte(), null)
                    } else {
                        DisconnectV5BodyCodec.decode(buffer)
                    }
                val reasonCode = getDisconnectCode(body.reasonCode ?: ReasonCode.NORMAL_DISCONNECTION.byte)
                val props = Properties.from(body.properties)
                return VariableHeader(reasonCode, props)
            }
        }
    }

    companion object {
        fun from(
            buffer: ReadBuffer,
            remainingLength: Int,
        ): DisconnectNotification {
            val variableHeader = VariableHeader.from(buffer, remainingLength)
            return DisconnectNotification(variableHeader)
        }
    }
}

private fun getDisconnectCode(byte: UByte): ReasonCode =
    when (byte) {
        ReasonCode.NORMAL_DISCONNECTION.byte -> ReasonCode.NORMAL_DISCONNECTION
        ReasonCode.DISCONNECT_WITH_WILL_MESSAGE.byte -> ReasonCode.DISCONNECT_WITH_WILL_MESSAGE
        ReasonCode.UNSPECIFIED_ERROR.byte -> ReasonCode.UNSPECIFIED_ERROR
        ReasonCode.MALFORMED_PACKET.byte -> ReasonCode.MALFORMED_PACKET
        ReasonCode.PROTOCOL_ERROR.byte -> ReasonCode.PROTOCOL_ERROR
        ReasonCode.IMPLEMENTATION_SPECIFIC_ERROR.byte -> ReasonCode.IMPLEMENTATION_SPECIFIC_ERROR
        ReasonCode.NOT_AUTHORIZED.byte -> ReasonCode.NOT_AUTHORIZED
        ReasonCode.SERVER_BUSY.byte -> ReasonCode.SERVER_BUSY
        ReasonCode.SERVER_SHUTTING_DOWN.byte -> ReasonCode.SERVER_SHUTTING_DOWN
        ReasonCode.KEEP_ALIVE_TIMEOUT.byte -> ReasonCode.KEEP_ALIVE_TIMEOUT
        ReasonCode.SESSION_TAKE_OVER.byte -> ReasonCode.SESSION_TAKE_OVER
        ReasonCode.TOPIC_FILTER_INVALID.byte -> ReasonCode.TOPIC_FILTER_INVALID
        ReasonCode.TOPIC_NAME_INVALID.byte -> ReasonCode.TOPIC_NAME_INVALID
        ReasonCode.RECEIVE_MAXIMUM_EXCEEDED.byte -> ReasonCode.RECEIVE_MAXIMUM_EXCEEDED
        ReasonCode.TOPIC_ALIAS_INVALID.byte -> ReasonCode.TOPIC_ALIAS_INVALID
        ReasonCode.PACKET_TOO_LARGE.byte -> ReasonCode.PACKET_TOO_LARGE
        ReasonCode.MESSAGE_RATE_TOO_HIGH.byte -> ReasonCode.MESSAGE_RATE_TOO_HIGH
        ReasonCode.QUOTA_EXCEEDED.byte -> ReasonCode.QUOTA_EXCEEDED
        ReasonCode.ADMINISTRATIVE_ACTION.byte -> ReasonCode.ADMINISTRATIVE_ACTION
        ReasonCode.PAYLOAD_FORMAT_INVALID.byte -> ReasonCode.PAYLOAD_FORMAT_INVALID
        ReasonCode.RETAIN_NOT_SUPPORTED.byte -> ReasonCode.RETAIN_NOT_SUPPORTED
        ReasonCode.QOS_NOT_SUPPORTED.byte -> ReasonCode.QOS_NOT_SUPPORTED
        ReasonCode.USE_ANOTHER_SERVER.byte -> ReasonCode.USE_ANOTHER_SERVER
        ReasonCode.SERVER_MOVED.byte -> ReasonCode.SERVER_MOVED
        ReasonCode.SHARED_SUBSCRIPTIONS_NOT_SUPPORTED.byte -> ReasonCode.SHARED_SUBSCRIPTIONS_NOT_SUPPORTED
        ReasonCode.CONNECTION_RATE_EXCEEDED.byte -> ReasonCode.CONNECTION_RATE_EXCEEDED
        ReasonCode.MAXIMUM_CONNECTION_TIME.byte -> ReasonCode.MAXIMUM_CONNECTION_TIME
        ReasonCode.SUBSCRIPTION_IDENTIFIERS_NOT_SUPPORTED.byte -> ReasonCode.SUBSCRIPTION_IDENTIFIERS_NOT_SUPPORTED
        ReasonCode.WILDCARD_SUBSCRIPTIONS_NOT_SUPPORTED.byte -> ReasonCode.WILDCARD_SUBSCRIPTIONS_NOT_SUPPORTED
        else -> throw MalformedPacketException("Invalid disconnect reason code $byte")
    }
