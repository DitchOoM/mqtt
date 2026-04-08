package com.ditchoom.mqtt5.controlpacket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes
import com.ditchoom.mqtt.MalformedPacketException
import com.ditchoom.mqtt.ProtocolError
import com.ditchoom.mqtt.codec.annotations.MqttProperties
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.variableByteSize
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
import com.ditchoom.mqtt5.controlpacket.properties.MqttProperty
import com.ditchoom.mqtt5.controlpacket.properties.PropertyExtractor
import com.ditchoom.mqtt5.controlpacket.properties.ReasonString
import com.ditchoom.mqtt5.controlpacket.properties.UserProperty
import com.ditchoom.mqtt5.controlpacket.properties.mqttPropertiesSize
import com.ditchoom.mqtt5.controlpacket.properties.readProperties
import kotlin.jvm.JvmInline

@ProtocolMessage
@JvmInline
value class UnsubAckReasonCodeV5(val raw: UByte)

@ProtocolMessage
data class UnsubAckV5Body(
    val packetIdentifier: UShort,
    @MqttProperties val properties: Collection<MqttProperty>?,
    @RemainingBytes val reasonCodes: List<UnsubAckReasonCodeV5>,
)

data class UnsubscribeAcknowledgment(
    val variable: VariableHeader,
    val reasonCodes: List<ReasonCode> = listOf(SUCCESS),
) : ControlPacketV5,
    IUnsubscribeAcknowledgment {
    override val controlPacketValue: Byte get() = 11
    override val direction: DirectionOfFlow get() = DirectionOfFlow.SERVER_TO_CLIENT
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
        reasonCodes: List<ReasonCode>,
    ) : this(VariableHeader(packetIdentifier, VariableHeader.Properties(reasonString, userProperty)), reasonCodes)

    override fun encodeBody(writeBuffer: WriteBuffer) {
        UnsubAckV5BodyCodec.encode(
            writeBuffer,
            UnsubAckV5Body(
                variable.packetIdentifier.toUShort(),
                variable.properties.props,
                reasonCodes.map { UnsubAckReasonCodeV5(it.byte) },
            ),
        )
    }

    override fun remainingLength(): Int {
        val variableSize = variable.size()
        val subSize = reasonCodes.size
        return variableSize + subSize
    }

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
        val properties: Properties = Properties(),
    ) {
        fun size() = UShort.SIZE_BYTES + variableByteSize(properties.size()) + properties.size()

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
                    val p = PropertyExtractor(keyValuePairs, "UNSUBACK")
                    val reasonString = p.single<ReasonString>()?.value
                    val userProperty = p.list<UserProperty>().map { it.key to it.value }
                    p.rejectUnknown()
                    return Properties(reasonString, userProperty)
                }
            }
        }

        companion object {
            fun from(buffer: ReadBuffer): Pair<Int, VariableHeader> {
                val packetIdentifier = buffer.readUnsignedShort()
                val startPos = buffer.position()
                val properties = buffer.readProperties()
                val propsBytes = buffer.position() - startPos
                val props = Properties.from(properties)
                return Pair(
                    UShort.SIZE_BYTES + propsBytes,
                    VariableHeader(packetIdentifier.toInt(), props),
                )
            }
        }
    }

    companion object {
        fun from(
            buffer: ReadBuffer,
            remainingLength: Int,
        ): UnsubscribeAcknowledgment {
            val wire = UnsubAckV5BodyCodec.decode(buffer)
            val props = VariableHeader.Properties.from(wire.properties)
            val variableHeader = VariableHeader(wire.packetIdentifier.toInt(), props)
            val list =
                wire.reasonCodes.map { rc ->
                    when (rc.raw) {
                        SUCCESS.byte -> SUCCESS
                        NO_SUBSCRIPTIONS_EXISTED.byte -> NO_SUBSCRIPTIONS_EXISTED
                        UNSPECIFIED_ERROR.byte -> UNSPECIFIED_ERROR
                        IMPLEMENTATION_SPECIFIC_ERROR.byte -> IMPLEMENTATION_SPECIFIC_ERROR
                        NOT_AUTHORIZED.byte -> NOT_AUTHORIZED
                        TOPIC_FILTER_INVALID.byte -> TOPIC_FILTER_INVALID
                        PACKET_IDENTIFIER_IN_USE.byte -> PACKET_IDENTIFIER_IN_USE
                        else -> throw MalformedPacketException(
                            "Invalid reason code ${rc.raw} " +
                                "see: https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477478",
                        )
                    }
                }
            return UnsubscribeAcknowledgment(variableHeader, list)
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
        PACKET_IDENTIFIER_IN_USE,
    )
}
