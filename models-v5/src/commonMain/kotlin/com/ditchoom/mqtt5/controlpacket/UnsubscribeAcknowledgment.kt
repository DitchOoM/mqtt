package com.ditchoom.mqtt5.controlpacket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.mqtt.MalformedPacketException
import com.ditchoom.mqtt.ProtocolError
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.variableByteSize
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.writeVariableByteInteger
import com.ditchoom.mqtt.controlpacket.IUnsubscribeAcknowledgment
import com.ditchoom.mqtt.controlpacket.format.ReasonCode
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.IMPLEMENTATION_SPECIFIC_ERROR
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.NOT_AUTHORIZED
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.NO_SUBSCRIPTIONS_EXISTED
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.PACKET_IDENTIFIER_IN_USE
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.SUCCESS
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.TOPIC_FILTER_INVALID
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.UNSPECIFIED_ERROR
import com.ditchoom.mqtt.controlpacket.format.fixed.DirectionOfFlow
import com.ditchoom.mqtt5.controlpacket.properties.Property
import com.ditchoom.mqtt5.controlpacket.properties.ReasonString
import com.ditchoom.mqtt5.controlpacket.properties.UserProperty
import com.ditchoom.mqtt5.controlpacket.properties.readPropertiesSized

data class UnsubscribeAcknowledgment(
    val variable: VariableHeader,
    val reasonCodes: List<ReasonCode> = listOf(SUCCESS)
) : ControlPacketV5(11, DirectionOfFlow.SERVER_TO_CLIENT), IUnsubscribeAcknowledgment {

    init {
        val invalidCodes = reasonCodes.map { it.byte } - validSubscribeCodes
        if (invalidCodes.isEmpty()) {
            throw ProtocolError("Invalid SUBACK reason code $invalidCodes")
        }
    }

    constructor(
        packetIdentifier: Int,
        reasonString: String? = null,
        userProperty: List<Pair<String, String>> = emptyList(),
        reasonCodes: List<ReasonCode>
    ) : this(VariableHeader(packetIdentifier, VariableHeader.Properties(reasonString, userProperty)), reasonCodes)

    override fun variableHeader(writeBuffer: WriteBuffer) = variable.serialize(writeBuffer)

    override fun remainingLength(): Int {
        val variableSize = variable.size()
        val subSize = reasonCodes.size
        return variableSize + subSize
    }

    override fun payload(writeBuffer: WriteBuffer) =
        reasonCodes.forEach { writeBuffer.writeUByte(it.byte) }

    override val packetIdentifier = variable.packetIdentifier

    /**
     * 3.11.2 UNSUBACK Variable Header
     *
     * The Variable Header of the UNSUBACK Packet the following fields in the order: the Packet Identifier from the
     * UNSUBSCRIBE Packet that is being acknowledged, and Properties. The rules for encoding Properties are described
     * in section 2.2.2.
     */

    data class VariableHeader(
        val packetIdentifier: Int,
        val properties: Properties = Properties()
    ) {
        fun size() = UShort.SIZE_BYTES + variableByteSize(properties.size()) + properties.size()

        fun serialize(writeBuffer: WriteBuffer) {
            writeBuffer.writeUShort(packetIdentifier.toUShort())
            properties.serialize(writeBuffer)
        }

        /**
         * 3.9.2.1 SUBACK Properties
         */

        data class Properties(
            /**
             * 3.11.2.1.2 Reason String
             *
             * 31 (0x1F) Byte, Identifier of the Reason String.
             *
             * Followed by the UTF-8 Encoded String representing the reason associated with this response. This
             * Reason String is a human readable string designed for diagnostics and SHOULD NOT be parsed by the
             * Client.
             *
             * The Server uses this value to give additional information to the Client. The Server MUST NOT send
             * this Property if it would increase the size of the UNSUBACK packet beyond the Maximum Packet Size
             * specified by the Client [MQTT-3.11.2-1]. It is a Protocol Error to include the Reason String more
             * than once.
             */
            val reasonString: String? = null,
            /**
             * 3.11.2.1.3 User Property
             *
             * 38 (0x26) Byte, Identifier of the User Property.
             *
             * Followed by UTF-8 String Pair. This property can be used to provide additional diagnostic or
             * other information. The Server MUST NOT send this property if it would increase the size of the
             * UNSUBACK packet beyond the Maximum Packet Size specified by the Client [MQTT-3.11.2-2]. The User
             * Property is allowed to appear multiple times to represent multiple name, value pairs. The same
             * name is allowed to appear more than once.
             */
            val userProperty: List<Pair<String, String>> = emptyList()
        ) {
            val props by lazy(LazyThreadSafetyMode.NONE) {
                val props = ArrayList<Property>(1 + userProperty.size)
                if (reasonString != null) {
                    props += ReasonString(reasonString)
                }
                if (userProperty.isNotEmpty()) {
                    for (keyValueProperty in userProperty) {
                        val key = keyValueProperty.first
                        val value = keyValueProperty.second
                        props += UserProperty(key, value)
                    }
                }
                props
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
                                                "https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477476"
                                    )
                                }
                                reasonString = it.diagnosticInfoDontParse
                            }

                            is UserProperty -> userProperty += Pair(it.key, it.value)
                            else -> throw MalformedPacketException("Invalid UnsubscribeAck property type found in MQTT properties $it")
                        }
                    }
                    return Properties(reasonString, userProperty)
                }
            }
        }

        companion object {
            fun from(buffer: ReadBuffer): Pair<Int, VariableHeader> {
                val packetIdentifier = buffer.readUnsignedShort()
                val sized = buffer.readPropertiesSized()
                val props = Properties.from(sized.second)
                return Pair(
                    UShort.SIZE_BYTES + variableByteSize(sized.first) + sized.first,
                    VariableHeader(packetIdentifier.toInt(), props)
                )
            }
        }
    }

    companion object {
        fun from(buffer: ReadBuffer, remainingLength: Int): UnsubscribeAcknowledgment {
            val variableHeader = VariableHeader.from(buffer)
            val list = mutableListOf<ReasonCode>()
            while (remainingLength - variableHeader.first > list.count()) {
                val reasonCodeByte = buffer.readUnsignedByte()
                list += when (reasonCodeByte) {
                    SUCCESS.byte -> SUCCESS
                    NO_SUBSCRIPTIONS_EXISTED.byte -> NO_SUBSCRIPTIONS_EXISTED
                    UNSPECIFIED_ERROR.byte -> UNSPECIFIED_ERROR
                    IMPLEMENTATION_SPECIFIC_ERROR.byte -> IMPLEMENTATION_SPECIFIC_ERROR
                    NOT_AUTHORIZED.byte -> NOT_AUTHORIZED
                    TOPIC_FILTER_INVALID.byte -> TOPIC_FILTER_INVALID
                    PACKET_IDENTIFIER_IN_USE.byte -> PACKET_IDENTIFIER_IN_USE
                    else -> throw MalformedPacketException(
                        "Invalid reason code $reasonCodeByte " +
                                "see: https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477478"
                    )
                }
            }
            return UnsubscribeAcknowledgment(variableHeader.second, list)
        }
    }
}

private val validSubscribeCodes by lazy(LazyThreadSafetyMode.NONE) {
    setOf(
        SUCCESS,
        NO_SUBSCRIPTIONS_EXISTED,
        UNSPECIFIED_ERROR,
        IMPLEMENTATION_SPECIFIC_ERROR,
        NOT_AUTHORIZED,
        TOPIC_FILTER_INVALID,
        PACKET_IDENTIFIER_IN_USE
    )
}
