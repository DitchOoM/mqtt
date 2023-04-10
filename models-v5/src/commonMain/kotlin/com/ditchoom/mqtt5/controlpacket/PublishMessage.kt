package com.ditchoom.mqtt5.controlpacket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.mqtt.MalformedPacketException
import com.ditchoom.mqtt.ProtocolError
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.readMqttUtf8StringNotValidatedSized
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.variableByteSize
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.writeMqttUtf8String
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.writeVariableByteInteger
import com.ditchoom.mqtt.controlpacket.IPublishMessage
import com.ditchoom.mqtt.controlpacket.NO_PACKET_ID
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.QualityOfService.AT_LEAST_ONCE
import com.ditchoom.mqtt.controlpacket.QualityOfService.AT_MOST_ONCE
import com.ditchoom.mqtt.controlpacket.Topic
import com.ditchoom.mqtt.controlpacket.format.ReasonCode
import com.ditchoom.mqtt.controlpacket.format.fixed.DirectionOfFlow
import com.ditchoom.mqtt.controlpacket.utf8Length
import com.ditchoom.mqtt.controlpacket.validControlPacketIdentifierRange
import com.ditchoom.mqtt5.controlpacket.properties.ContentType
import com.ditchoom.mqtt5.controlpacket.properties.CorrelationData
import com.ditchoom.mqtt5.controlpacket.properties.MessageExpiryInterval
import com.ditchoom.mqtt5.controlpacket.properties.PayloadFormatIndicator
import com.ditchoom.mqtt5.controlpacket.properties.Property
import com.ditchoom.mqtt5.controlpacket.properties.ResponseTopic
import com.ditchoom.mqtt5.controlpacket.properties.SubscriptionIdentifier
import com.ditchoom.mqtt5.controlpacket.properties.TopicAlias
import com.ditchoom.mqtt5.controlpacket.properties.UserProperty
import com.ditchoom.mqtt5.controlpacket.properties.readPropertiesSized

/**
 * Creates an MQTT PUBLISH
 *
 */

data class PublishMessage(
    val fixed: FixedHeader = FixedHeader(),
    val variable: VariableHeader,
    override val payload: ReadBuffer? = null
) :
    ControlPacketV5(IPublishMessage.controlPacketValue, DirectionOfFlow.BIDIRECTIONAL, fixed.flags),
    IPublishMessage {

    constructor(
        dup: Boolean = false,
        qos: QualityOfService = AT_MOST_ONCE,
        retain: Boolean = false,
        topicName: String,
        packetIdentifier: Int = NO_PACKET_ID,
        payloadFormatIndicator: Boolean = false,
        messageExpiryInterval: Long? = null,
        topicAlias: Int? = null,
        responseTopicName: String? = null,
        correlationData: ReadBuffer? = null,
        userProperty: List<Pair<String, String>> = emptyList(),
        subscriptionIdentifier: Set<Long> = emptySet(),
        contentType: String? = null,
        payload: ReadBuffer? = null
    ) : this(
        FixedHeader(dup, qos, retain),
        VariableHeader(
            Topic.fromOrThrow(topicName, Topic.Type.Name),
            packetIdentifier,
            VariableHeader.Properties(
                payloadFormatIndicator,
                messageExpiryInterval,
                topicAlias,
                responseTopicName?.let { Topic.fromOrThrow(it, Topic.Type.Name) },
                correlationData,
                userProperty,
                subscriptionIdentifier,
                contentType
            )
        ),
        payload
    )

    override val packetIdentifier = variable.packetIdentifier
    override fun setDupFlagNewPubMessage(): IPublishMessage {
        return if (fixed.qos == AT_MOST_ONCE && fixed.dup) {
            copy(fixed = fixed.copy(dup = false), variable = variable, payload = payload)
        } else if (fixed.qos != AT_MOST_ONCE && !fixed.dup) {
            copy(fixed = fixed.copy(dup = true), variable = variable, payload = payload)
        } else {
            this
        }
    }

    override val qualityOfService: QualityOfService = fixed.qos
    override fun variableHeader(writeBuffer: WriteBuffer) = variable.serialize(writeBuffer)
    override fun payload(writeBuffer: WriteBuffer) {
        if (payload != null) {
            writeBuffer.write(payload)
        }
    }

    override fun remainingLength(): Int {
        var size = variable.size()
        if (payload != null) {
            size += payload.remaining()
        }
        return size
    }

    override val topic = variable.topicName

    override fun expectedResponse(
        reasonCode: ReasonCode,
        reasonString: String?,
        userProperty: List<Pair<String, String>>
    ) = when (fixed.qos) {
        AT_LEAST_ONCE -> {
            PublishAcknowledgment(
                PublishAcknowledgment.VariableHeader(
                    variable.packetIdentifier,
                    reasonCode,
                    PublishAcknowledgment.VariableHeader.Properties(reasonString, userProperty)
                )
            )
        }

        QualityOfService.EXACTLY_ONCE -> {
            PublishReceived(
                PublishReceived.VariableHeader(
                    packetIdentifier,
                    reasonCode,
                    PublishReceived.VariableHeader.Properties(reasonString, userProperty)
                )
            )
        }

        else -> null
    }

    override fun maybeCopyWithNewPacketIdentifier(packetIdentifier: Int): IPublishMessage = when (qualityOfService) {
        AT_MOST_ONCE -> this
        AT_LEAST_ONCE,
        QualityOfService.EXACTLY_ONCE -> copy(variable = variable.copy(packetIdentifier = packetIdentifier))
    }

    data class FixedHeader(
        /**
         * 3.3.1.1 DUP
         *
         * Position: byte 1, bit 3.
         *
         * If the DUP flag is set to 0, it indicates that this is the first occasion that the Client or Server
         * has attempted to send this PUBLISH packet. If the DUP flag is set to 1, it indicates that this might
         * be re-delivery of an earlier attempt to send the packet.
         *
         * The DUP flag MUST be set to 1 by the Client or Server when it attempts to re-deliver a PUBLISH packet
         * [MQTT-3.3.1-1]. The DUP flag MUST be set to 0 for all QoS 0 messages [MQTT-3.3.1-2].
         *
         * The value of the DUP flag from an incoming PUBLISH packet is not propagated when the PUBLISH packet is
         * sent to subscribers by the Server. The DUP flag in the outgoing PUBLISH packet is set independently to
         * the incoming PUBLISH packet, its value MUST be determined solely by whether the outgoing PUBLISH packet
         * is a retransmission [MQTT-3.3.1-3].
         *
         * Non-normative comment
         *
         * The receiver of an MQTT Control Packet that contains the DUP flag set to 1 cannot assume that it has
         * seen an earlier copy of this packet.
         *
         * Non-normative comment
         *
         * It is important to note that the DUP flag refers to the MQTT Control Packet itself and not to the
         * Application Message that it contains. When using QoS 1, it is possible for a Client to receive a
         * PUBLISH packet with DUP flag set to 0 that contains a repetition of an Application Message that it
         * received earlier, but with a different Packet Identifier. Section 2.2.1 provides more information
         * about Packet Identifiers.
         */
        val dup: Boolean = false,
        /**
         * 3.3.1.2 QoS
         *
         * Position: byte 1, bits 2-1.
         *
         * This field indicates the level of assurance for delivery of an Application Message. The QoS levels are
         * shown below.
         *
         * If the Server included a Maximum QoS in its CONNACK response to a Client and it receives a PUBLISH packet
         * with a QoS greater than this, then it uses DISCONNECT with Reason Code 0x9B (QoS not supported) as
         * described in section 4.13 Handling errors.
         *
         * A PUBLISH Packet MUST NOT have both QoS bits set to 1 [MQTT-3.3.1-4]. If a Server or Client receives a
         * PUBLISH packet which has both QoS bits set to 1 it is a Malformed Packet. Use DISCONNECT with Reason Code
         * 0x81 (Malformed Packet) as described in section 4.13.
         */
        val qos: QualityOfService = AT_MOST_ONCE,
        /**
         * 3.3.1.3 RETAIN
         *
         * Position: byte 1, bit 0.
         *
         * If the RETAIN flag is set to 1 in a PUBLISH packet sent by a Client to a Server, the Server MUST replace
         * any existing retained message for this topic and store the Application Message [MQTT-3.3.1-5], so that it
         * can be delivered to future subscribers whose subscriptions match its Topic Name. If the Payload contains
         * zero bytes it is processed normally by the Server but any retained message with the same topic name MUST
         * be removed and any future subscribers for the topic will not receive a retained message [MQTT-3.3.1-6]. A
         * retained message with a Payload containing zero bytes MUST NOT be stored as a retained message on the
         * Server [MQTT-3.3.1-7].
         *
         * If the RETAIN flag is 0 in a PUBLISH packet sent by a Client to a Server, the Server MUST NOT store the
         * message as a retained message and MUST NOT remove or replace any existing retained message [MQTT-3.3.1-8].
         *
         * If the Server included Retain Available in its CONNACK response to a Client with its value set to 0 and it
         * receives a PUBLISH packet with the RETAIN flag is set to 1, then it uses the DISCONNECT Reason Code of
         * 0x9A (Retain not supported) as described in section 4.13.
         *
         * When a new Non‑shared Subscription is made, the last retained message, if any, on each matching topic name
         * is sent to the Client as directed by the Retain Handling Subscription Option. These messages are sent with
         * the RETAIN flag set to 1. Which retained messages are sent is controlled by the Retain Handling Subscription
         * Option. At the time of the Subscription:
         *
         * ·         If Retain Handling is set to 0 the Server MUST send the retained messages matching the Topic
         * Filter of the subscription to the Client [MQTT-3.3.1-9].
         *
         * ·         If Retain Handling is set to 1 then if the subscription did not already exist, the Server MUST
         * send all retained message matching the Topic Filter of the subscription to the Client, and if the
         * subscription did exist the Server MUST NOT send the retained messages. [MQTT-3.3.1-10].
         *
         * ·         If Retain Handling is set to 2, the Server MUST NOT send the retained messages [MQTT-3.3.1-11].
         *
         * Refer to section 3.8.3.1 for a definition of the Subscription Options.
         *
         * If the Server receives a PUBLISH packet with the RETAIN flag set to 1, and QoS 0 it SHOULD store the new
         * QoS 0 message as the new retained message for that topic, but MAY choose to discard it at any time. If
         * this happens there will be no retained message for that topic.
         *
         * If the current retained message for a Topic expires, it is discarded and there will be no retained message
         * for that topic.
         *
         * The setting of the RETAIN flag in an Application Message forwarded by the Server from an established
         * connection is controlled by the Retain As Published subscription option. Refer to section 3.8.3.1 for
         * a definition of the Subscription Options.
         *
         * ·         If the value of Retain As Published subscription option is set to 0, the Server MUST set the
         * RETAIN flag to 0 when forwarding an Application Message regardless of how the RETAIN flag was set in the
         * received PUBLISH packet [MQTT-3.3.1-12].
         *
         * ·         If the value of Retain As Published subscription option is set to 1, the Server MUST set the
         * RETAIN flag equal to the RETAIN flag in the received PUBLISH packet [MQTT-3.3.1-13].
         *
         * Non-normative comment
         *
         * Retained messages are useful where publishers send state messages on an irregular basis. A new non-shared
         * subscriber will receive the most recent state.
         */
        val retain: Boolean = false
    ) {
        val flags by lazy(LazyThreadSafetyMode.NONE) {
            val dupInt = if (dup) 0b1000 else 0b0
            val qosInt = qos.integerValue.toInt().shl(1)
            val retainInt = if (retain) 0b1 else 0b0
            (dupInt or qosInt or retainInt).toByte()
        }

        companion object {
            fun fromByte(byte1: UByte): FixedHeader {
                val byte1Int = byte1.toInt()
                val dup = byte1Int.shl(4).toUByte().toInt().shr(7) == 1
                val qosBit2 = byte1Int.shl(5).toUByte().toInt().shr(7) == 1
                val qosBit1 = byte1Int.shl(6).toUByte().toInt().shr(7) == 1
                if (qosBit2 && qosBit1) {
                    throw MalformedPacketException(
                        "A PUBLISH Packet MUST NOT have both QoS bits set to 1 [MQTT-3.3.1-4]." +
                                " If a Server or Client receives a PUBLISH packet which has both QoS bits set to 1 it is a " +
                                "Malformed Packet. Use DISCONNECT with Reason Code 0x81 (Malformed Packet) as described in" +
                                " section 4.13."
                    )
                }
                val qos = QualityOfService.fromBooleans(qosBit2, qosBit1)
                val retain = byte1Int.shl(7).toUByte().toInt().shr(7) == 1
                return FixedHeader(dup, qos, retain)
            }
        }
    }

    /**
     * 3.3.2 PUBLISH Variable Header
     *
     * The Variable Header of the PUBLISH Packet contains the following fields in the order: Topic Name, Packet
     * Identifier, and Properties. The rules for encoding Properties are described in section 2.2.2.
     */

    data class VariableHeader(
        val topicName: Topic,
        val packetIdentifier: Int = NO_PACKET_ID,
        val properties: Properties = Properties()
    ) {

        init {
            if (properties.topicAlias == 0) {
                throw ProtocolError(
                    "Topic Alias not permitted to be set to 0:" +
                            "https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477413"
                )
            }
        }

        override fun equals(other: Any?): Boolean {
            if (other !is VariableHeader) return false
            return when {
                topicName != other.topicName -> {
                    false
                }

                packetIdentifier != other.packetIdentifier -> {
                    false
                }

                properties != other.properties -> {
                    false
                }

                else -> true
            }
        }

        fun serialize(buffer: WriteBuffer) {
            buffer.writeMqttUtf8String(topicName.toString())
            if (packetIdentifier in validControlPacketIdentifierRange) {
                buffer.writeUShort(packetIdentifier.toUShort())
            }
            properties.serialize(buffer)
        }

        fun size(): Int {
            var size = UShort.SIZE_BYTES + topicName.toString().utf8Length()
            if (packetIdentifier in validControlPacketIdentifierRange) {
                size += UShort.SIZE_BYTES
            }
            val propsSize = properties.size()
            size += variableByteSize(propsSize) + propsSize
            return size
        }

        override fun hashCode(): Int {
            var result = topicName.hashCode()
            result = 31 * result + packetIdentifier
            result = 31 * result + properties.hashCode()
            return result
        }

        data class Properties(
            /**
             * 3.3.2.3.2 Payload Format Indicator
             *
             * 1 (0x01) Byte, Identifier of the Payload Format Indicator.
             *
             * Followed by the value of the Payload Format Indicator, either of:
             *
             * ·         0 (0x00) Byte Indicates that the Payload is unspecified bytes, which
             * is equivalent to not sending a Payload Format Indicator.
             *
             * ·         1 (0x01) Byte Indicates that the Payload is UTF-8 Encoded Character
             * Data. The UTF-8 data in the Payload MUST be well-formed UTF-8 as defined by the
             * Unicode specification [Unicode] and restated in RFC 3629 [RFC3629].
             *
             * A Server MUST send the Payload Format Indicator unaltered to all subscribers
             * receiving the Application Message [MQTT-3.3.2-4]. The receiver MAY validate
             * that the Payload is of the format indicated, and if it is not send a PUBACK,
             * PUBREC, or DISCONNECT with Reason Code of 0x99 (Payload format invalid) as
             * described in section 4.13.  Refer to section 5.4.9 for information about
             * security issues in validating the payload format.
             */
            val payloadFormatIndicator: Boolean = false,
            /**
             * 3.3.2.3.3 Message Expiry Interval`
             *
             * 2 (0x02) Byte, Identifier of the Message Expiry Interval.
             *
             * Followed by the Four Byte Integer representing the Message Expiry Interval.
             *
             * If present, the Four Byte value is the lifetime of the Application Message in
             * seconds. If the Message Expiry Interval has passed and the Server has not
             * managed to start onward delivery to a matching subscriber, then it MUST delete
             * the copy of the message for that subscriber [MQTT-3.3.2-5].
             *
             * If absent, the Application Message does not expire.
             *
             * The PUBLISH packet sent to a Client by the Server MUST contain a Message Expiry
             * Interval set to the received value minus the time that the Application Message
             * has been waiting in the Server [MQTT-3.3.2-6]. Refer to section 4.1 for details
             * and limitations of stored state.
             */
            val messageExpiryInterval: Long? = null,
            /**
             * 3.3.2.3.4 Topic Alias
             *
             * 35 (0x23) Byte, Identifier of the Topic Alias.
             *
             * Followed by the Two Byte integer representing the Topic Alias value. It is a Protocol Error to
             * include the Topic Alias value more than once.
             *
             * A Topic Alias is an integer value that is used to identify the Topic instead of using the Topic
             * Name. This reduces the size of the PUBLISH packet, and is useful when the Topic Names are long
             * and the same Topic Names are used repetitively within a Network Connection.
             *
             * The sender decides whether to use a Topic Alias and chooses the value. It sets a Topic Alias mapping
             * by including a non-zero length Topic Name and a Topic Alias in the PUBLISH packet. The receiver
             * processes the PUBLISH as normal but also sets the specified Topic Alias mapping to this Topic Name.
             *
             * If a Topic Alias mapping has been set at the receiver, a sender can send a PUBLISH packet that
             * contains that Topic Alias and a zero length Topic Name. The receiver then treats the incoming
             * PUBLISH as if it had contained the Topic Name of the Topic Alias.
             *
             * A sender can modify the Topic Alias mapping by sending another PUBLISH in the same Network
             * Connection with the same Topic Alias value and a different non-zero length Topic Name.
             *
             * Topic Alias mappings exist only within a Network Connection and last only for the lifetime of that
             * Network Connection. A receiver MUST NOT carry forward any Topic Alias mappings from one Network
             * Connection to another [MQTT-3.3.2-7].
             *
             * A Topic Alias of 0 is not permitted. A sender MUST NOT send a PUBLISH packet containing a Topic
             * Alias which has the value 0 [MQTT-3.3.2-8].
             *
             * A Client MUST NOT send a PUBLISH packet with a Topic Alias greater than the Topic Alias Maximum
             * value returned by the Server in the CONNACK packet [MQTT-3.3.2-9]. A Client MUST accept all Topic
             * Alias values greater than 0 and less than or equal to the Topic Alias Maximum value that it sent
             * in the CONNECT packet [MQTT-3.3.2-10].
             *
             * A Server MUST NOT send a PUBLISH packet with a Topic Alias greater than the Topic Alias Maximum
             * value sent by the Client in the CONNECT packet [MQTT-3.3.2-11]. A Server MUST accept all Topic
             * Alias values greater than 0 and less than or equal to the Topic Alias Maximum value that it
             * returned in the CONNACK packet [MQTT-3.3.2-12].
             *
             * The Topic Alias mappings used by the Client and Server are independent from each other. Thus, when
             * a Client sends a PUBLISH containing a Topic Alias value of 1 to a Server and the Server sends a
             * PUBLISH with a Topic Alias value of 1 to that Client they will in general be referring to
             * different Topics.
             */
            val topicAlias: Int? = null,
            /**
             * 3.3.2.3.5 Response Topic
             *
             * 8 (0x08) Byte, Identifier of the Response Topic.
             *
             * Followed by a UTF-8 Encoded String which is used as the Topic Name for a response message. The
             * Response Topic MUST be a UTF-8 Encoded String as defined in section 1.5.4 [MQTT-3.3.2-13]. The
             * Response Topic MUST NOT contain wildcard characters [MQTT-3.3.2-14]. It is a Protocol Error to
             * include the Response Topic more than once. The presence of a Response Topic identifies the Message
             * as a Request.
             *
             * Refer to section 4.10 for more information about Request / Response.
             *
             * The Server MUST send the Response Topic unaltered to all subscribers receiving the Application
             * Message [MQTT-3.3.2-15].
             *
             * Non-normative comment:
             *
             * The receiver of an Application Message with a Response Topic sends a response by using the
             * Response Topic as the Topic Name of a PUBLISH. If the Request Message contains a Correlation
             * Data, the receiver of the Request Message should also include this Correlation Data as a
             * property in the PUBLISH packet of the Response Message.
             */
            val responseTopic: Topic? = null,
            /**
             * 3.3.2.3.6 Correlation Data
             *
             * 9 (0x09) Byte, Identifier of the Correlation Data.
             *
             * Followed by Binary Data. The Correlation Data is used by the sender of the Request Message to
             * identify which request the Response Message is for when it is received. It is a Protocol Error
             * to include Correlation Data more than once. If the Correlation Data is not present, the Requester
             * does not require any correlation data.
             *
             * The Server MUST send the Correlation Data unaltered to all subscribers receiving the Application
             * Message [MQTT-3.3.2-16]. The value of the Correlation Data only has meaning to the sender of the
             * Request Message and receiver of the Response Message.
             *
             * Non-normative comment
             *
             * The receiver of an Application Message which contains both a Response Topic and a Correlation Data
             * sends a response by using the Response Topic as the Topic Name of a PUBLISH. The Client should also
             * send the Correlation Data unaltered as part of the PUBLISH of the responses.
             *
             * Non-normative comment
             *
             * If the Correlation Data contains information which can cause application failures if modified by
             * the Client responding to the request, it should be encrypted and/or hashed to allow any alteration
             * to be detected.
             *
             * Refer to section 4.10 for more information about Request / Response
             */
            val correlationData: ReadBuffer? = null,
            /**
             * 3.3.2.3.7 User Property
             *
             * 38 (0x26) Byte, Identifier of the User Property.
             *
             * Followed by a UTF-8 String Pair. The User Property is allowed to appear multiple times to
             * represent multiple name, value pairs. The same name is allowed to appear more than once.
             *
             * The Server MUST send all User Properties unaltered in a PUBLISH packet when forwarding the
             * Application Message to a Client [MQTT-3.3.2-17]. The Server MUST maintain the order of User
             * Properties when forwarding the Application Message [MQTT-3.3.2-18].
             *
             * Non-normative comment
             *
             * This property is intended to provide a means of transferring application layer name-value tags
             * whose meaning and interpretation are known only by the application programs responsible for
             * sending and receiving them.
             */
            val userProperty: List<Pair<String, String>> = emptyList(),
            /**
             * 3.3.2.3.8 Subscription Identifier
             *
             * 11 (0x0B), Identifier of the Subscription Identifier.
             *
             * Followed by a Variable Byte Integer representing the identifier of the subscription.
             *
             * The Subscription Identifier can have the value of 1 to 268,435,455. It is a Protocol Error if the
             * Subscription Identifier has a value of 0. Multiple Subscription Identifiers will be included if
             * the publication is the result of a match to more than one subscription, in this case their order
             * is not significant.
             */
            val subscriptionIdentifier: Set<Long> = emptySet(),
            /**
             * 3.3.2.3.9 Content Type
             *
             * 3 (0x03) Identifier of the Content Type.
             *
             * Followed by a UTF-8 Encoded String describing the content of the Application Message. The Content
             * Type MUST be a UTF-8 Encoded String as defined in section 1.5.4 [MQTT-3.3.2-19].
             *
             * It is a Protocol Error to include the Content Type more than once. The value of the Content Type is
             * defined by the sending and receiving application.
             *
             * A Server MUST send the Content Type unaltered to all subscribers receiving the Application Message
             * [MQTT-3.3.2-20].
             *
             * Non-normative comment
             *
             * The UTF-8 Encoded String may use a MIME content type string to describe the contents of the
             * Application message. However, since the sending and receiving applications are responsible for
             * the definition and interpretation of the string, MQTT performs no validation of the string except
             * to insure it is a valid UTF-8 Encoded String.
             *
             * Non-normative example
             *
             * Figure 3-9 shows an example of a PUBLISH packet with the Topic Name set to “a/b”, the Packet
             * Identifier set to 10, and having no properties.
             */
            val contentType: String? = null
        ) {

            init {
                if (topicAlias == 0) {
                    throw ProtocolError(
                        "Topic Alias not permitted to be set to 0:" +
                                "https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477413"
                    )
                }
            }

            val props by lazy(LazyThreadSafetyMode.NONE) {
                val list = ArrayList<Property>(7 + userProperty.count())
                if (payloadFormatIndicator) {
                    list += PayloadFormatIndicator(payloadFormatIndicator)
                }
                if (messageExpiryInterval != null) {
                    list += MessageExpiryInterval(messageExpiryInterval)
                }
                if (topicAlias != null) {
                    list += TopicAlias(topicAlias)
                }
                if (responseTopic != null) {
                    list += ResponseTopic(responseTopic)
                }
                if (correlationData != null) {
                    list += CorrelationData(correlationData)
                }
                if (userProperty.isNotEmpty()) {
                    for (keyValueProperty in userProperty) {
                        val key = keyValueProperty.first
                        val value = keyValueProperty.second
                        list += UserProperty(key, value)
                    }
                }
                if (subscriptionIdentifier.isNotEmpty()) {
                    for (sub in subscriptionIdentifier) {
                        list += SubscriptionIdentifier(sub)
                    }
                }
                if (contentType != null) {
                    list += ContentType(contentType)
                }
                list
            }

            fun size(): Int {
                var size = 0
                props.forEach { size += it.size() }
                return size
            }

            fun serialize(buffer: WriteBuffer) {
                val size = size()
                buffer.writeVariableByteInteger(size)
                props.forEach { it.write(buffer) }
            }

            companion object {
                fun from(keyValuePairs: Collection<Property>?): Properties {
                    var payloadFormatIndicator: Boolean? = null
                    var messageExpiryInterval: Long? = null
                    var topicAlias: Int? = null
                    var responseTopic: Topic? = null
                    var correlationData: ReadBuffer? = null
                    val userProperty = mutableListOf<Pair<String, String>>()
                    val subscriptionIdentifier = LinkedHashSet<Long>()
                    var contentType: String? = null
                    keyValuePairs?.forEach {
                        when (it) {
                            is PayloadFormatIndicator -> {
                                if (payloadFormatIndicator != null) {
                                    throw ProtocolError("Payload Indicator Format found twice")
                                }
                                payloadFormatIndicator = it.willMessageIsUtf8
                            }

                            is MessageExpiryInterval -> {
                                if (messageExpiryInterval != null) {
                                    throw ProtocolError("Message Expiry Interval found twice")
                                }
                                messageExpiryInterval = it.seconds
                            }

                            is TopicAlias -> {
                                if (topicAlias != null) {
                                    throw ProtocolError(
                                        "Topic Alias found twice see:" +
                                                "https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477413"
                                    )
                                }
                                if (it.value == 0) {
                                    throw ProtocolError(
                                        "Topic Alias not permitted to be set to 0:" +
                                                "https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477413"
                                    )
                                }
                                topicAlias = it.value
                            }

                            is ResponseTopic -> {
                                if (responseTopic != null) {
                                    throw ProtocolError(
                                        "Response Topic found twice see:" +
                                                "https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477414"
                                    )
                                }
                                responseTopic = it.value
                            }

                            is CorrelationData -> {
                                if (correlationData != null) {
                                    throw ProtocolError(
                                        "Correlation Data found twice see:" +
                                                "https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477415"
                                    )
                                }
                                correlationData = it.data
                            }

                            is UserProperty -> userProperty += Pair(it.key, it.value)
                            is SubscriptionIdentifier -> {
                                if (it.value == 0L) {
                                    throw ProtocolError(
                                        "Subscription Identifier not permitted to be set to 0:" +
                                                "https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477417"
                                    )
                                }
                                subscriptionIdentifier.add(it.value)
                            }

                            is ContentType -> {
                                if (contentType != null) {
                                    throw ProtocolError(
                                        "Content Type found twice see:" +
                                                "https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477417"
                                    )
                                }
                                contentType = it.value
                            }

                            else -> throw MalformedPacketException("Invalid property type found in MQTT properties $it")
                        }
                    }
                    return Properties(
                        payloadFormatIndicator ?: false,
                        messageExpiryInterval,
                        topicAlias,
                        responseTopic,
                        correlationData,
                        userProperty,
                        subscriptionIdentifier,
                        contentType
                    )
                }
            }
        }

        companion object {

            fun from(buffer: ReadBuffer, isQos0: Boolean): Pair<Int, VariableHeader> {
                val result = buffer.readMqttUtf8StringNotValidatedSized()
                var size = result.first + UShort.SIZE_BYTES
                val topicName = result.second
                val packetIdentifier = if (isQos0) {
                    NO_PACKET_ID
                } else {
                    size += 2
                    buffer.readUnsignedShort().toInt()
                }
                val propertiesSized = buffer.readPropertiesSized()
                size += 1
                size += propertiesSized.first
                val props = Properties.from(propertiesSized.second)
                return Pair(
                    size,
                    VariableHeader(Topic.fromOrThrow(topicName, Topic.Type.Name), packetIdentifier, props)
                )
            }
        }
    }

    companion object {
        fun from(buffer: ReadBuffer, byte1: UByte, remainingLength: Int): PublishMessage {
            val fixedHeader = FixedHeader.fromByte(byte1)
            val variableHeaderSized = VariableHeader.from(buffer, fixedHeader.qos == AT_MOST_ONCE)
            val variableHeader = variableHeaderSized.second
            val variableSize = variableHeaderSized.first
            val size = remainingLength - variableSize
            val payloadBuffer = if (size > 0) {
                buffer.readBytes(size)
            } else {
                null
            }
            return PublishMessage(fixedHeader, variableHeader, payloadBuffer)
        }
    }
}
