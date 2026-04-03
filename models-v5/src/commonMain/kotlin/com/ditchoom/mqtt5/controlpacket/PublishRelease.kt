package com.ditchoom.mqtt5.controlpacket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.mqtt.MalformedPacketException
import com.ditchoom.mqtt.ProtocolError
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.variableByteSize
import com.ditchoom.mqtt.controlpacket.IPublishRelease
import com.ditchoom.mqtt.controlpacket.format.ReasonCode
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.PACKET_IDENTIFIER_NOT_FOUND
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.SUCCESS
import com.ditchoom.mqtt.controlpacket.format.fixed.DirectionOfFlow
import com.ditchoom.mqtt5.controlpacket.properties.MqttProperty
import com.ditchoom.mqtt5.controlpacket.properties.PropertyExtractor
import com.ditchoom.mqtt5.controlpacket.properties.ReasonString
import com.ditchoom.mqtt5.controlpacket.properties.UserProperty
import com.ditchoom.mqtt5.controlpacket.properties.mqttPropertiesSize
import com.ditchoom.mqtt5.controlpacket.wire.AckV5Wire
import com.ditchoom.mqtt5.controlpacket.wire.AckV5WireCodec

/**
 * 3.6 PUBREL – Publish release (QoS 2 delivery part 2)
 *
 * A PUBREL packet is the response to a PUBREC packet. It is the third packet of the QoS 2 protocol exchange.
 */

data class PublishRelease(
    val variable: VariableHeader,
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
    ) :
        this(VariableHeader(packetIdentifier, reasonCode, VariableHeader.Properties(reasonString, userProperty)))

    override val packetIdentifier: Int = variable.packetIdentifier

    override fun expectedResponse(
        reasonCode: ReasonCode,
        reasonString: String?,
        userProperty: List<Pair<String, String>>,
    ) = PublishComplete(
        PublishComplete.VariableHeader(
            variable.packetIdentifier,
            reasonCode,
            PublishComplete.VariableHeader.Properties(reasonString, userProperty),
        ),
    )

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

    override fun remainingLength() = variable.size().toInt()

    /**
     * 3.6.2 PUBREL Variable Header
     *
     * The Variable Header of the PUBREL Packet contains the following fields in the order: the Packet Identifier from
     * the PUBREC packet that is being acknowledged, PUBREL Reason Code, and Properties. The rules for encoding
     * Properties are described in section 2.2.2.
     */

    data class VariableHeader(
        val packetIdentifier: Int,
        /**
         * 3.6.2.1 PUBREL Reason Code
         *
         * Byte 3 in the Variable Header is the PUBREL Reason Code. If the Remaining Length is
         * 2, the value of 0x00 (Success) is used.
         * The Client or Server sending the PUBREL packet MUST use one of the PUBREL Reason Code
         * values [MQTT-3.6.2-1]. The Reason Code and Property Length can be omitted if the
         * Reason Code is 0x00 (Success) and there are no Properties. In this case the
         * PUBREL has a Remaining Length of 2.
         */
        val reasonCode: ReasonCode = SUCCESS,
        /**
         * 3.4.2.2 PUBREL Properties
         */
        val properties: Properties = Properties(),
    ) {
        init {
            when (reasonCode.byte.toInt()) {
                0, 0x92 -> {
                }

                else -> throw ProtocolError(
                    "Invalid Publish Release reason code ${reasonCode.byte} " +
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
             * 3.6.2.2.2 Reason String
             *
             * 31 (0x1F) Byte, Identifier of the Reason String.
             *
             * Followed by the UTF-8 Encoded String representing the reason associated with this response. This
             * Reason String is human readable, designed for diagnostics and SHOULD NOT be parsed by the receiver.
             *
             * The sender uses this value to give additional information to the receiver. The sender MUST NOT send
             * this Property if it would increase the size of the PUBREL packet beyond the Maximum Packet Size
             * specified by the receiver [MQTT-3.6.2-2]. It is a Protocol Error to include the Reason String more
             * than once.
             */
            val reasonString: String? = null,
            /**
             * 3.6.2.2.3 User Property
             *
             * 38 (0x26) Byte, Identifier of the User Property.
             *
             * Followed by UTF-8 String Pair. This property can be used to provide additional diagnostic or other
             * information for the PUBREL. The sender MUST NOT send this property if it would increase the size of
             * the PUBREL packet beyond the Maximum Packet Size specified by the receiver [MQTT-3.6.2-3]. The User
             * Property is allowed to appear multiple times to represent multiple name, value pairs. The same name
             * is allowed to appear more than once.
             */
            val userProperty: List<Pair<String, String>> = emptyList(),
        ) {
            val props: List<MqttProperty> = buildList {
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
            }

            fun size(): Int = mqttPropertiesSize(props)

            companion object {
                fun from(keyValuePairs: Collection<MqttProperty>?): Properties {
                    val p = PropertyExtractor(keyValuePairs, "PUBREL")
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
                remaining: Int,
            ): VariableHeader {
                if (remaining == 2) {
                    val packetIdentifier = buffer.readUnsignedShort().toInt()
                    return VariableHeader(packetIdentifier)
                }
                val wire =
                    if (remaining == 3) {
                        AckV5Wire(buffer.readUnsignedShort(), buffer.readUnsignedByte(), null)
                    } else {
                        AckV5WireCodec.decode(buffer)
                    }
                val reasonCode =
                    when (wire.reasonCode) {
                        SUCCESS.byte -> SUCCESS
                        PACKET_IDENTIFIER_NOT_FOUND.byte -> PACKET_IDENTIFIER_NOT_FOUND
                        else -> throw MalformedPacketException(
                            "Invalid reason code ${wire.reasonCode}" +
                                "see: https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477444",
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
        ) = PublishRelease(VariableHeader.from(buffer, remainingLength))
    }
}
