package com.ditchoom.mqtt5.controlpacket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes
import com.ditchoom.buffer.utf8Length
import com.ditchoom.mqtt.codec.annotations.MqttProperties
import com.ditchoom.mqtt.ProtocolError
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.variableByteSize
import com.ditchoom.mqtt.controlpacket.IUnsubscribeRequest
import com.ditchoom.mqtt.controlpacket.NO_PACKET_ID
import com.ditchoom.mqtt.controlpacket.TopicFilter
import com.ditchoom.mqtt.controlpacket.format.fixed.DirectionOfFlow
import com.ditchoom.mqtt5.controlpacket.properties.MqttProperty
import com.ditchoom.mqtt5.controlpacket.properties.PropertyExtractor
import com.ditchoom.mqtt5.controlpacket.properties.UserProperty
import com.ditchoom.mqtt5.controlpacket.properties.mqttPropertiesSize
import com.ditchoom.mqtt5.controlpacket.properties.readProperties

@ProtocolMessage
data class TopicFilterV5Entry(
    @LengthPrefixed val topicFilter: String,
)

@ProtocolMessage
data class UnsubscribeV5Body(
    val packetIdentifier: UShort,
    @MqttProperties val properties: Collection<MqttProperty>?,
    @RemainingBytes val topics: List<TopicFilterV5Entry>,
)

/**
 * 3.10 UNSUBSCRIBE – Unsubscribe request
 * An UNSUBSCRIBE packet is sent by the Client to the Server, to unsubscribe from topics.
 */

data class UnsubscribeRequest(
    val variable: VariableHeader,
    override val topics: Set<TopicFilter>,
) : ControlPacketV5,
    IUnsubscribeRequest {
    override val controlPacketValue: Byte get() = IUnsubscribeRequest.controlPacketValue
    override val direction: DirectionOfFlow get() = DirectionOfFlow.CLIENT_TO_SERVER
    override val flags: Byte get() = 0b10
    constructor(
        topics: Set<TopicFilter>,
        userProperty: List<Pair<String, String>> = emptyList(),
    ) : this(VariableHeader(NO_PACKET_ID, VariableHeader.Properties(userProperty)), topics)

    constructor(topic: String, userProperty: List<Pair<String, String>> = emptyList()) :
        this(
            VariableHeader(NO_PACKET_ID, VariableHeader.Properties(userProperty)),
            setOf<TopicFilter>(TopicFilter.fromOrThrow(topic)),
        )

    init {
        if (topics.isEmpty()) {
            throw ProtocolError("An UNSUBSCRIBE packet with no Payload is a Protocol Error")
        }
    }

    override fun copyWithNewPacketIdentifier(packetIdentifier: Int): IUnsubscribeRequest =
        copy(variable = variable.copy(packetIdentifier = packetIdentifier))

    override fun encodeBody(writeBuffer: WriteBuffer) {
        UnsubscribeV5BodyCodec.encode(
            writeBuffer,
            UnsubscribeV5Body(
                variable.packetIdentifier.toUShort(),
                variable.properties.props,
                topics.map { TopicFilterV5Entry(it.toString()) },
            ),
        )
    }

    override fun remainingLength(): Int {
        val variableSize = variable.size()
        var payloadSize = 0
        topics.forEach { payloadSize += UShort.SIZE_BYTES + it.toString().utf8Length() }
        return variableSize + payloadSize
    }

    override val packetIdentifier = variable.packetIdentifier

    /**
     * 3.10.2 UNSUBSCRIBE Variable Header
     *
     * The Variable Header of the UNSUBSCRIBE Packet contains the following fields in the order: Packet Identifier,
     * and Properties. Section 2.2.1 provides more information about Packet Identifiers. The rules for encoding
     * Properties are described in section 2.2.2.
     */

    data class VariableHeader(
        val packetIdentifier: Int,
        val properties: Properties = Properties(),
    ) {
        fun size() = UShort.SIZE_BYTES + variableByteSize(properties.size()) + properties.size()

        /**
         * 3.10.2.1 UNSUBSCRIBE Properties
         */

        data class Properties(
            /**
             * 3.10.2.1.2 User Property
             *
             * 38 (0x26) Byte, Identifier of the User Property.
             *
             * Followed by a UTF-8 String Pair.
             *
             * The User Property is allowed to appear multiple times to represent multiple name, value pairs. The
             * same name is allowed to appear more than once.
             *
             * Non-normative comment
             *
             * User Properties on the UNSUBSCRIBE packet can be used to send subscription related properties from
             * the Client to the Server. The meaning of these properties is not defined by this specification.
             */
            val userProperty: List<Pair<String, String>> = emptyList(),
        ) {
            val props: List<MqttProperty> = buildList {
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
                    val p = PropertyExtractor(keyValuePairs, "UNSUBSCRIBE")
                    val userProperty = p.list<UserProperty>().map { it.key to it.value }
                    p.rejectUnknown()
                    return Properties(userProperty)
                }
            }
        }

        companion object {
            fun from(buffer: ReadBuffer): Pair<Int, VariableHeader> {
                val packetIdentifier = buffer.readUnsignedShort().toInt()
                val startPos = buffer.position()
                val properties = buffer.readProperties()
                val propsBytes = buffer.position() - startPos
                val props = Properties.from(properties)
                return Pair(
                    propsBytes + UShort.SIZE_BYTES,
                    VariableHeader(packetIdentifier, props),
                )
            }
        }
    }

    companion object {
        fun from(
            buffer: ReadBuffer,
            remainingLength: Int,
        ): UnsubscribeRequest {
            val wire = UnsubscribeV5BodyCodec.decode(buffer)
            val props = VariableHeader.Properties.from(wire.properties)
            val header = VariableHeader(wire.packetIdentifier.toInt(), props)
            val topics = wire.topics.map { TopicFilter.fromOrThrow(it.topicFilter) }.toSet()
            return UnsubscribeRequest(header, topics)
        }
    }
}
