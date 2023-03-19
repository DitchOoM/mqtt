package com.ditchoom.mqtt5.controlpacket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.mqtt.MalformedPacketException
import com.ditchoom.mqtt.ProtocolError
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.variableByteSize
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.writeVariableByteInteger
import com.ditchoom.mqtt.controlpacket.IPublishComplete
import com.ditchoom.mqtt.controlpacket.format.ReasonCode
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.PACKET_IDENTIFIER_NOT_FOUND
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.SUCCESS
import com.ditchoom.mqtt.controlpacket.format.fixed.DirectionOfFlow
import com.ditchoom.mqtt5.controlpacket.properties.Property
import com.ditchoom.mqtt5.controlpacket.properties.ReasonString
import com.ditchoom.mqtt5.controlpacket.properties.UserProperty
import com.ditchoom.mqtt5.controlpacket.properties.readProperties

/**
 * 3.7 PUBCOMP – Publish complete (QoS 2 delivery part 3)
 *
 * The PUBCOMP packet is the response to a PUBREL packet. It is the fourth and final packet of the QoS 2 protocol exchange.
 */

data class PublishComplete(val variable: VariableHeader) :
    ControlPacketV5(7, DirectionOfFlow.BIDIRECTIONAL), IPublishComplete {
    constructor(packetIdentifier: UShort, reasonCode: ReasonCode = SUCCESS) :
        this(VariableHeader(packetIdentifier.toInt(), reasonCode))

    constructor(
        packetIdentifier: Int,
        reasonCode: ReasonCode = SUCCESS,
        reasonString: String? = null,
        userProperty: List<Pair<String, String>> = emptyList()
    ) :
        this(
            VariableHeader(packetIdentifier, reasonCode, VariableHeader.Properties(reasonString, userProperty))
        )

    override fun variableHeader(writeBuffer: WriteBuffer) = variable.serialize(writeBuffer)
    override val packetIdentifier = variable.packetIdentifier
    override fun remainingLength() = variable.size().toInt()

    /**
     * 3.7.2 PUBCOMP Variable Header
     *
     * The Variable Header of the PUBCOMP Packet contains the following fields in the order: Packet Identifier from
     * the PUBREL packet that is being acknowledged, PUBCOMP Reason Code, and Properties. The rules for encoding
     * Properties are described in section 2.2.2.
     */

    data class VariableHeader(
        val packetIdentifier: Int,
        /**
         * 3.7.2.1 PUBCOMP Reason Code
         *
         * Byte 3 in the Variable Header is the PUBCOMP Reason Code. If the Remaining Length is
         * 2, then the value 0x00 (Success) is used.
         *
         * The Client or Server sending the PUBCOMP packet MUST use one of the PUBCOMP Reason
         * Code values [MQTT-3.7.2-1]. The Reason Code and Property Length can be omitted if the
         * Reason Code is 0x00 (Success) and there are no Properties. In this case the PUBCOMP
         * has a Remaining Length of 2.
         */
        val reasonCode: ReasonCode = SUCCESS,
        /**
         * 3.4.2.2 PUBACK Properties
         */
        val properties: Properties = Properties()
    ) {
        init {
            when (reasonCode.byte.toInt()) {
                0, 0x92 -> {
                }

                else -> throw ProtocolError(
                    "Invalid Publish Acknowledgment reason code ${reasonCode.byte} " +
                        "see: https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477424"
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

        data class Properties(
            /**
             * 3.7.2.2.2 Reason String
             *
             * 31 (0x1F) Byte, Identifier of the Reason String.
             *
             * Followed by the UTF-8 Encoded String representing the reason associated with this response. This
             * Reason String is a human readable string designed for diagnostics and SHOULD NOT be parsed by the
             * receiver.
             *
             * The sender uses this value to give additional information to the receiver. The sender MUST NOT send
             * this Property if it would increase the size of the PUBCOMP packet beyond the Maximum Packet Size
             * specified by the receiver [MQTT-3.7.2-2]. It is a Protocol Error to include the Reason String more
             * than once.
             */
            val reasonString: String? = null,
            /**
             * 3.7.2.2.3 User Property
             *
             * 38 (0x26) Byte, Identifier of the User Property.
             *
             * Followed by UTF-8 String Pair. This property can be used to provide additional diagnostic or other
             * information. The sender MUST NOT send this property if it would increase the size of the PUBCOMP
             * packet beyond the Maximum Packet Size specified by the receiver [MQTT-3.7.2-3]. The User Property
             * is allowed to appear multiple times to represent multiple name, value pairs. The same name is
             * allowed to appear more than once.
             */
            val userProperty: List<Pair<String, String>> = emptyList()
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
                                            "https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477427"
                                    )
                                }
                                reasonString = it.diagnosticInfoDontParse
                            }

                            is UserProperty -> userProperty += Pair(it.key, it.value)
                            else -> throw MalformedPacketException("Invalid Publish Complete property type found in MQTT properties $it")
                        }
                    }
                    return Properties(reasonString, userProperty)
                }
            }
        }

        companion object {
            fun from(buffer: ReadBuffer, remainingLength: Int): VariableHeader {
                val packetIdentifier = buffer.readUnsignedShort()
                return if (remainingLength == 2) {
                    VariableHeader(packetIdentifier.toInt())
                } else {
                    val reasonCodeByte = buffer.readUnsignedByte()
                    val reasonCode = when (reasonCodeByte) {
                        SUCCESS.byte -> SUCCESS
                        PACKET_IDENTIFIER_NOT_FOUND.byte -> PACKET_IDENTIFIER_NOT_FOUND
                        else -> throw MalformedPacketException(
                            "Invalid reason code $reasonCodeByte" +
                                "see: https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477444"
                        )
                    }
                    val propsData = buffer.readProperties()
                    val props = Properties.from(propsData)
                    VariableHeader(packetIdentifier.toInt(), reasonCode, props)
                }
            }
        }
    }

    companion object {
        fun from(buffer: ReadBuffer, remainingLength: Int) =
            PublishComplete(VariableHeader.from(buffer, remainingLength))
    }
}
