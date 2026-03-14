package com.ditchoom.mqtt5.controlpacket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.utf8Length
import com.ditchoom.mqtt.MalformedPacketException
import com.ditchoom.mqtt.ProtocolError
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.readMqttUtf8StringNotValidatedSized
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.variableByteSize
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.writeMqttUtf8String
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.writeVariableByteInteger
import com.ditchoom.mqtt.controlpacket.IUnsubscribeRequest
import com.ditchoom.mqtt.controlpacket.NO_PACKET_ID
import com.ditchoom.mqtt.controlpacket.TopicFilter
import com.ditchoom.mqtt.controlpacket.format.fixed.DirectionOfFlow
import com.ditchoom.mqtt5.controlpacket.properties.Property
import com.ditchoom.mqtt5.controlpacket.properties.UserProperty
import com.ditchoom.mqtt5.controlpacket.properties.readPropertiesSized
import com.ditchoom.mqtt5.controlpacket.wire.TopicFilterV5Wire
import com.ditchoom.mqtt5.controlpacket.wire.UnsubscribeV5Wire
import com.ditchoom.mqtt5.controlpacket.wire.UnsubscribeV5WireCodec

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

    override fun variableHeader(writeBuffer: WriteBuffer) = variable.serialize(writeBuffer)

    override fun encodeBody(writeBuffer: WriteBuffer) {
        UnsubscribeV5WireCodec.encode(
            writeBuffer,
            UnsubscribeV5Wire(
                variable.packetIdentifier.toUShort(),
                variable.properties.props,
                topics.map { TopicFilterV5Wire(it.toString()) },
            ),
        )
    }

    override fun remainingLength(): Int {
        val variableSize = variable.size()
        var payloadSize = 0
        topics.forEach { payloadSize += UShort.SIZE_BYTES + it.toString().utf8Length() }
        return variableSize + payloadSize
    }

    override fun payload(writeBuffer: WriteBuffer) = topics.forEach { writeBuffer.writeMqttUtf8String(it.toString()) }

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

        fun serialize(writeBuffer: WriteBuffer) {
            writeBuffer.writeUShort(packetIdentifier.toUShort())
            properties.serialize(writeBuffer)
        }

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
            val props by lazy(LazyThreadSafetyMode.NONE) {
                val props = ArrayList<Property>(userProperty.size)
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
                    val userProperty = mutableListOf<Pair<String, String>>()
                    keyValuePairs?.forEach {
                        when (it) {
                            is UserProperty -> userProperty += Pair(it.key, it.value)
                            else -> throw MalformedPacketException("Invalid Unsubscribe Request property type found in MQTT properties $it")
                        }
                    }
                    return Properties(userProperty)
                }
            }
        }

        companion object {
            fun from(buffer: ReadBuffer): Pair<Int, VariableHeader> {
                val packetIdentifier = buffer.readUnsignedShort().toInt()
                val sized = buffer.readPropertiesSized()
                val props = Properties.from(sized.second)
                return Pair(
                    sized.first + variableByteSize(sized.first) + UShort.SIZE_BYTES,
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
            val wire = UnsubscribeV5WireCodec.decode(buffer)
            val props = VariableHeader.Properties.from(wire.properties)
            val header = VariableHeader(wire.packetIdentifier.toInt(), props)
            val topics = wire.topics.map { TopicFilter.fromOrThrow(it.topicFilter) }.toSet()
            return UnsubscribeRequest(header, topics)
        }
    }
}
