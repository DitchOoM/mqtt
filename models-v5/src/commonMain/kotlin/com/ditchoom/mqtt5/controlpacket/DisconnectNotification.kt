package com.ditchoom.mqtt5.controlpacket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.mqtt.MalformedPacketException
import com.ditchoom.mqtt.ProtocolError
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.variableByteSize
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.writeVariableByteInteger
import com.ditchoom.mqtt.controlpacket.IDisconnectNotification
import com.ditchoom.mqtt.controlpacket.format.ReasonCode
import com.ditchoom.mqtt.controlpacket.format.fixed.DirectionOfFlow
import com.ditchoom.mqtt5.controlpacket.properties.Property
import com.ditchoom.mqtt5.controlpacket.properties.ReasonString
import com.ditchoom.mqtt5.controlpacket.properties.ServerReference
import com.ditchoom.mqtt5.controlpacket.properties.SessionExpiryInterval
import com.ditchoom.mqtt5.controlpacket.properties.UserProperty
import com.ditchoom.mqtt5.controlpacket.properties.readProperties

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

data class DisconnectNotification(val variable: VariableHeader = VariableHeader()) :
    ControlPacketV5(14, DirectionOfFlow.BIDIRECTIONAL), IDisconnectNotification {

    override fun packetSize(): Int = 2 + remainingLength()
    override fun variableHeader(writeBuffer: WriteBuffer) = variable.serialize(writeBuffer)

    override fun remainingLength(): Int = variable.size()
    data class VariableHeader(
        val reasonCode: ReasonCode = ReasonCode.NORMAL_DISCONNECTION,
        val properties: Properties = Properties()
    ) {
        init {
            // throw if the reason code is not valid for the disconnect notification
            getDisconnectCode(reasonCode.byte)
        }

        fun size(): Int {
            val propertiesSize = properties.size()
            return UByte.SIZE_BYTES + variableByteSize(propertiesSize) + propertiesSize
        }

        fun serialize(buffer: WriteBuffer) {
            buffer.writeUByte(reasonCode.byte)
            properties.serialize(buffer)
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
            val serverReference: String? = null
        ) {
            val props by lazy(LazyThreadSafetyMode.NONE) {
                val list = ArrayList<Property>(3 + userProperty.count())
                if (sessionExpiryIntervalSeconds != null) {
                    list += SessionExpiryInterval(sessionExpiryIntervalSeconds)
                }
                if (reasonString != null) {
                    list += ReasonString(reasonString)
                }
                if (userProperty.isNotEmpty()) {
                    for (keyValueProperty in userProperty) {
                        val key = keyValueProperty.first
                        val value = keyValueProperty.second
                        list += UserProperty(key, value)
                    }
                }
                if (serverReference != null) {
                    list += ServerReference(serverReference)
                }
                list
            }

            fun size(): Int {
                var size = 0
                props.forEach { size += it.size() }
                return size
            }

            fun serialize(buffer: WriteBuffer) {
                buffer.writeVariableByteInteger(size())
                props.forEach { it.write(buffer) }
            }

            companion object {
                fun from(keyValuePairs: Collection<Property>?): Properties {
                    var sessionExpiryIntervalSeconds: ULong? = null
                    var reasonString: String? = null
                    val userProperty = mutableListOf<Pair<String, String>>()
                    var serverReference: String? = null
                    keyValuePairs?.forEach {
                        when (it) {
                            is SessionExpiryInterval -> {
                                if (sessionExpiryIntervalSeconds != null) {
                                    throw ProtocolError(
                                        "Session Expiry Interval added multiple times see: " +
                                                "https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477382"
                                    )
                                }
                                sessionExpiryIntervalSeconds = it.seconds
                            }

                            is ReasonString -> {
                                if (reasonString != null) {
                                    throw ProtocolError(
                                        "Reason String added multiple times see: " +
                                                "https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477476"
                                    )
                                }
                                reasonString = it.diagnosticInfoDontParse
                            }

                            is UserProperty -> userProperty.add(Pair(it.key, it.value))
                            is ServerReference -> {
                                if (serverReference != null) {
                                    throw ProtocolError(
                                        "Server Reference added multiple times see: " +
                                                "https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477396"
                                    )
                                }
                                serverReference = it.otherServer
                            }

                            else -> throw MalformedPacketException("Invalid UnsubscribeAck property type found in MQTT properties $it")
                        }
                    }
                    return Properties(
                        sessionExpiryIntervalSeconds,
                        reasonString,
                        userProperty,
                        serverReference
                    )
                }
            }
        }

        companion object {
            fun from(buffer: ReadBuffer): VariableHeader {
                val reasonCodeByte = buffer.readUnsignedByte()
                val reasonCode = getDisconnectCode(reasonCodeByte)
                val props = Properties.from(buffer.readProperties())
                return VariableHeader(reasonCode, props)
            }
        }
    }

    companion object {
        fun from(buffer: ReadBuffer): DisconnectNotification {
            val variableHeader = VariableHeader.from(buffer)
            return DisconnectNotification(variableHeader)
        }
    }
}

private fun getDisconnectCode(byte: UByte): ReasonCode {
    return when (byte) {
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
}
