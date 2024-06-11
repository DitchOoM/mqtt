package com.ditchoom.mqtt5.controlpacket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.mqtt.MalformedPacketException
import com.ditchoom.mqtt.ProtocolError
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.variableByteSize
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.writeVariableByteInteger
import com.ditchoom.mqtt.controlpacket.IPublishAcknowledgment
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
import com.ditchoom.mqtt5.controlpacket.properties.Property
import com.ditchoom.mqtt5.controlpacket.properties.ReasonString
import com.ditchoom.mqtt5.controlpacket.properties.UserProperty
import com.ditchoom.mqtt5.controlpacket.properties.readProperties

/**
 * 3.4 PUBACK – Publish acknowledgement
 *
 * A PUBACK packet is the response to a PUBLISH packet with QoS 1.
 */

data class PublishAcknowledgment(val variable: VariableHeader) :
    ControlPacketV5(4, DirectionOfFlow.BIDIRECTIONAL),
    IPublishAcknowledgment {
    constructor(packetIdentifier: UShort) : this(VariableHeader(packetIdentifier.toInt()))
    constructor(
        packetId: Int,
        reasonCode: ReasonCode = SUCCESS,
        reasonString: String? = null,
        props: List<Pair<String, String>> = emptyList(),
    ) :
        this(VariableHeader(packetId, reasonCode, VariableHeader.Properties(reasonString, props)))

    override fun variableHeader(writeBuffer: WriteBuffer) = variable.serialize(writeBuffer)

    override val packetIdentifier: Int = variable.packetIdentifier

    override fun remainingLength() = variable.size()

    data class VariableHeader(
        val packetIdentifier: Int,
        /**
         * 3.4.2.1 PUBACK Reason Code
         *
         * Byte 3 in the Variable Header is the PUBACK Reason Code. If the Remaining Length is 2,
         * then there is no Reason Code and the value of 0x00 (Success) is used.
         *
         * The Client or Server sending the PUBACK packet MUST use one of the PUBACK Reason Codes
         * [MQTT-3.4.2-1]. The Reason Code and Property Length can be omitted if the Reason Code
         * is 0x00 (Success) and there are no Properties. In this case the PUBACK has a Remaining
         * Length of 2.
         */
        val reasonCode: ReasonCode = SUCCESS,
        /**
         * 3.4.2.2 PUBACK Properties
         */
        val properties: Properties = Properties(),
    ) {
        init {
            when (reasonCode.byte.toInt()) {
                0, 0x10, 0x80, 0x83, 0x87, 0x90, 0x91, 0x97, 0x99 -> {
                }

                else -> throw ProtocolError(
                    "Invalid Publish Acknowledgment reason code ${reasonCode.byte} " +
                        "see: https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477424",
                )
            }
        }

        fun serialize(buffer: WriteBuffer) {
            val canOmitReasonCodeAndProperties = (
                reasonCode == SUCCESS &&
                    properties.userProperty.isEmpty() &&
                    properties.reasonString == null
            )
            buffer.writeUShort(packetIdentifier.toUShort())
            if (!canOmitReasonCodeAndProperties) {
                buffer.writeUByte(reasonCode.byte)
                properties.serialize(buffer)
            }
        }

        fun size(): Int {
            val canOmitReasonCodeAndProperties = (
                reasonCode == SUCCESS &&
                    properties.userProperty.isEmpty() &&
                    properties.reasonString == null
            )
            var size = UShort.SIZE_BYTES
            if (!canOmitReasonCodeAndProperties) {
                val propsSize = properties.size()
                size += UByte.SIZE_BYTES + variableByteSize(propsSize) + propsSize
            }
            return size
        }

        data class Properties(
            /**
             * 3.4.2.2.2 Reason String
             *
             * 31 (0x1F) Byte, Identifier of the Reason String.
             *
             * Followed by the UTF-8 Encoded String representing the reason associated with this response. This
             * Reason String is a human readable string designed for diagnostics and is not intended to be parsed
             * by the receiver.
             *
             * The sender uses this value to give additional information to the receiver. The sender MUST NOT send
             * this property if it would increase the size of the PUBACK packet beyond the Maximum Packet Size
             * specified by the receiver [MQTT-3.4.2-2]. It is a Protocol Error to include the Reason String more
             * than once.
             */
            val reasonString: String? = null,
            /**
             * 3.4.2.2.3 User Property
             *
             * 38 (0x26) Byte, Identifier of the User Property.
             *
             * Followed by UTF-8 String Pair. This property can be used to provide additional diagnostic or
             * other information. The sender MUST NOT send this property if it would increase the size of the
             * PUBACK packet beyond the Maximum Packet Size specified by the receiver [MQTT-3.4.2-3]. The User
             * Property is allowed to appear multiple times to represent multiple name, value pairs. The same
             * name is allowed to appear more than once.
             */
            val userProperty: List<Pair<String, String>> = emptyList(),
        ) {
            val props by lazy(LazyThreadSafetyMode.NONE) {
                val list = ArrayList<Property>(1 + userProperty.count())
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
                    var reasonString: String? = null
                    val userProperty = mutableListOf<Pair<String, String>>()
                    keyValuePairs?.forEach {
                        when (it) {
                            is ReasonString -> {
                                if (reasonString != null) {
                                    throw ProtocolError(
                                        "Reason String added multiple times see: " +
                                            "https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477427",
                                    )
                                }
                                reasonString = it.diagnosticInfoDontParse
                            }

                            is UserProperty -> userProperty += Pair(it.key, it.value)
                            else -> throw MalformedPacketException("Invalid Publish Ack property type found in MQTT properties $it")
                        }
                    }
                    return Properties(reasonString, userProperty)
                }
            }
        }

        companion object {
            fun from(
                buffer: ReadBuffer,
                remainingLength: Int,
            ): VariableHeader {
                val packetIdentifier = buffer.readUnsignedShort()
                if (remainingLength == 2) {
                    return VariableHeader(packetIdentifier.toInt())
                } else {
                    val reasonCodeByte = buffer.readUnsignedByte()
                    val reasonCode =
                        when (reasonCodeByte) {
                            SUCCESS.byte -> SUCCESS
                            NO_MATCHING_SUBSCRIBERS.byte -> NO_MATCHING_SUBSCRIBERS
                            UNSPECIFIED_ERROR.byte -> UNSPECIFIED_ERROR
                            IMPLEMENTATION_SPECIFIC_ERROR.byte -> IMPLEMENTATION_SPECIFIC_ERROR
                            NOT_AUTHORIZED.byte -> NOT_AUTHORIZED
                            TOPIC_NAME_INVALID.byte -> TOPIC_NAME_INVALID
                            PACKET_IDENTIFIER_IN_USE.byte -> PACKET_IDENTIFIER_IN_USE
                            QUOTA_EXCEEDED.byte -> QUOTA_EXCEEDED
                            PAYLOAD_FORMAT_INVALID.byte -> PAYLOAD_FORMAT_INVALID
                            else -> throw MalformedPacketException(
                                "Invalid reason code $reasonCodeByte" +
                                    "see: https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477424",
                            )
                        }
                    val props =
                        if (buffer.hasRemaining()) {
                            val propsData = buffer.readProperties()
                            Properties.from(propsData)
                        } else {
                            Properties()
                        }
                    return VariableHeader(packetIdentifier.toInt(), reasonCode, props)
                }
            }
        }
    }

    companion object {
        fun from(
            buffer: ReadBuffer,
            remainingLength: Int,
        ) = PublishAcknowledgment(VariableHeader.from(buffer, remainingLength))
    }
}
