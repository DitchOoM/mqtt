package com.ditchoom.mqtt5.controlpacket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.mqtt.MalformedPacketException
import com.ditchoom.mqtt.ProtocolError
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.variableByteSize
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
import com.ditchoom.mqtt5.controlpacket.properties.MqttProperty
import com.ditchoom.mqtt5.controlpacket.properties.PropertyExtractor
import com.ditchoom.mqtt5.controlpacket.properties.ReasonString
import com.ditchoom.mqtt5.controlpacket.properties.UserProperty
import com.ditchoom.mqtt5.controlpacket.properties.mqttPropertiesSize
import com.ditchoom.mqtt5.controlpacket.wire.AckV5Wire
import com.ditchoom.mqtt5.controlpacket.wire.AckV5WireCodec

/**
 * 3.4 PUBACK – Publish acknowledgement
 *
 * A PUBACK packet is the response to a PUBLISH packet with QoS 1.
 */

data class PublishAcknowledgment(
    val variable: VariableHeader,
) : ControlPacketV5,
    IPublishAcknowledgment {
    override val controlPacketValue: Byte get() = 4
    override val direction: DirectionOfFlow get() = DirectionOfFlow.BIDIRECTIONAL
    constructor(packetIdentifier: UShort) : this(VariableHeader(packetIdentifier.toInt()))
    constructor(
        packetId: Int,
        reasonCode: ReasonCode = SUCCESS,
        reasonString: String? = null,
        props: List<Pair<String, String>> = emptyList(),
    ) :
        this(VariableHeader(packetId, reasonCode, VariableHeader.Properties(reasonString, props)))

    override fun encodeBody(writeBuffer: WriteBuffer) {
        val canOmit = variable.reasonCode == SUCCESS &&
            variable.properties.userProperty.isEmpty() &&
            variable.properties.reasonString == null
        if (canOmit) {
            writeBuffer.writeUShort(variable.packetIdentifier.toUShort())
        } else {
            AckV5WireCodec.encode(
                writeBuffer,
                AckV5Wire(
                    variable.packetIdentifier.toUShort(),
                    variable.reasonCode.byte,
                    variable.properties.props,
                ),
            )
        }
    }

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
                val list = ArrayList<MqttProperty>(1 + userProperty.count())
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

            fun size(): Int = mqttPropertiesSize(props)

            companion object {
                fun from(keyValuePairs: Collection<MqttProperty>?): Properties {
                    val p = PropertyExtractor(keyValuePairs, "PUBACK")
                    val reasonString = p.single<ReasonString>()?.value
                    val userProperty = p.list<UserProperty>().map { it.key to it.value }
                    p.rejectUnknown()
                    return Properties(reasonString, userProperty)
                }
            }
        }

        companion object {
            fun from(
                buffer: ReadBuffer,
                remainingLength: Int,
            ): VariableHeader {
                if (remainingLength == 2) {
                    val packetIdentifier = buffer.readUnsignedShort()
                    return VariableHeader(packetIdentifier.toInt())
                }
                val wire =
                    if (remainingLength == 3) {
                        AckV5Wire(buffer.readUnsignedShort(), buffer.readUnsignedByte(), null)
                    } else {
                        AckV5WireCodec.decode(buffer)
                    }
                val reasonCode =
                    when (wire.reasonCode) {
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
                            "Invalid reason code ${wire.reasonCode}" +
                                "see: https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477424",
                        )
                    }
                val props = Properties.from(wire.properties)
                return VariableHeader(wire.packetId.toInt(), reasonCode, props)
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
