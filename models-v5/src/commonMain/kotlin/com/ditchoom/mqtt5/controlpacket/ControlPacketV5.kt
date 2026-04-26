package com.ditchoom.mqtt5.controlpacket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.annotations.DispatchOn
import com.ditchoom.buffer.codec.annotations.LengthPrefix
import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.Payload
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes
import com.ditchoom.buffer.codec.annotations.WhenRemaining
import com.ditchoom.buffer.codec.annotations.WhenTrue
import com.ditchoom.buffer.utf8Length
import com.ditchoom.buffer.writeLengthPrefixedUtf8String
import com.ditchoom.buffer.writeVariableByteIntegerLengthPrefixed
import com.ditchoom.mqtt.MalformedPacketException
import com.ditchoom.mqtt.MqttWarning
import com.ditchoom.mqtt.ProtocolError
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.variableByteSize
import com.ditchoom.mqtt.controlpacket.IConnectionAcknowledgment
import com.ditchoom.mqtt.controlpacket.IConnectionRequest
import com.ditchoom.mqtt.controlpacket.IDisconnectNotification
import com.ditchoom.mqtt.controlpacket.IPingRequest
import com.ditchoom.mqtt.controlpacket.IPingResponse
import com.ditchoom.mqtt.controlpacket.IPublishAcknowledgment
import com.ditchoom.mqtt.controlpacket.IPublishComplete
import com.ditchoom.mqtt.controlpacket.IPublishReceived
import com.ditchoom.mqtt.controlpacket.IPublishRelease
import com.ditchoom.mqtt.controlpacket.ISubscribeAcknowledgement
import com.ditchoom.mqtt.controlpacket.ISubscribeRequest
import com.ditchoom.mqtt.controlpacket.ISubscription
import com.ditchoom.mqtt.controlpacket.ISubscription.RetainHandling
import com.ditchoom.mqtt.controlpacket.ISubscription.RetainHandling.DO_NOT_SEND_RETAINED_MESSAGES
import com.ditchoom.mqtt.controlpacket.ISubscription.RetainHandling.SEND_RETAINED_MESSAGES_AT_SUBSCRIBE_ONLY_IF_SUBSCRIBE_DOESNT_EXISTS
import com.ditchoom.mqtt.controlpacket.ISubscription.RetainHandling.SEND_RETAINED_MESSAGES_AT_TIME_OF_SUBSCRIBE
import com.ditchoom.mqtt.controlpacket.IUnsubscribeAcknowledgment
import com.ditchoom.mqtt.controlpacket.IUnsubscribeRequest
import com.ditchoom.mqtt.controlpacket.NO_PACKET_ID
import com.ditchoom.mqtt.controlpacket.PublishMessage
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.TopicFilter
import com.ditchoom.mqtt.controlpacket.TopicName
import com.ditchoom.mqtt.controlpacket.WillConfig
import com.ditchoom.mqtt.controlpacket.format.ReasonCode
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.ADMINISTRATIVE_ACTION
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.CONNECTION_RATE_EXCEEDED
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.CONTINUE_AUTHENTICATION
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.DISCONNECT_WITH_WILL_MESSAGE
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.IMPLEMENTATION_SPECIFIC_ERROR
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.KEEP_ALIVE_TIMEOUT
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.MALFORMED_PACKET
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.MAXIMUM_CONNECTION_TIME
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.MESSAGE_RATE_TOO_HIGH
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.NORMAL_DISCONNECTION
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.NOT_AUTHORIZED
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.NO_MATCHING_SUBSCRIBERS
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.PACKET_IDENTIFIER_IN_USE
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.PACKET_IDENTIFIER_NOT_FOUND
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.PACKET_TOO_LARGE
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.PAYLOAD_FORMAT_INVALID
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.PROTOCOL_ERROR
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.QOS_NOT_SUPPORTED
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.QUOTA_EXCEEDED
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.REAUTHENTICATE
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.RECEIVE_MAXIMUM_EXCEEDED
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.RETAIN_NOT_SUPPORTED
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.SERVER_BUSY
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.SERVER_MOVED
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.SERVER_SHUTTING_DOWN
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.SESSION_TAKE_OVER
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.SHARED_SUBSCRIPTIONS_NOT_SUPPORTED
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.SUBSCRIPTION_IDENTIFIERS_NOT_SUPPORTED
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.SUCCESS
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.TOPIC_ALIAS_INVALID
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.TOPIC_FILTER_INVALID
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.TOPIC_NAME_INVALID
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.UNSPECIFIED_ERROR
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.USE_ANOTHER_SERVER
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.WILDCARD_SUBSCRIPTIONS_NOT_SUPPORTED
import com.ditchoom.mqtt.controlpacket.format.fixed.DirectionOfFlow
import com.ditchoom.mqtt5.controlpacket.properties.AuthenticationDataCodec
import com.ditchoom.mqtt5.controlpacket.properties.CorrelationDataCodec
import com.ditchoom.mqtt5.controlpacket.properties.MqttProperty
import com.ditchoom.mqtt5.controlpacket.properties.MqttPropertyCodec
import com.ditchoom.mqtt5.controlpacket.properties.ReasonString
import com.ditchoom.mqtt5.controlpacket.properties.ServerReference
import com.ditchoom.mqtt5.controlpacket.properties.SessionExpiryInterval
import com.ditchoom.mqtt5.controlpacket.properties.UserProperty
import com.ditchoom.mqtt5.controlpacket.properties.mqttPropertiesSize
import kotlin.jvm.JvmInline

// ── Wire-shape element types for list-payload packets ─────────────────────

@ProtocolMessage
data class SubscriptionV5Entry(
    @LengthPrefixed val topicFilter: String,
    val subscriptionOptions: UByte,
)

@ProtocolMessage
data class TopicFilterV5Entry(
    @LengthPrefixed val topicFilter: String,
)

@JvmInline
@ProtocolMessage
value class SubAckReasonCodeV5(
    val raw: UByte,
)

@JvmInline
@ProtocolMessage
value class UnsubAckReasonCodeV5(
    val raw: UByte,
)

/** CONNECT flags byte (§3.1.2.3). */
@JvmInline
value class ConnectFlagsV5(
    val raw: UByte,
) {
    val reserved: Boolean get() = raw.toInt() and 1 == 1
    val cleanStart: Boolean get() = (raw.toInt() shr 1) and 1 == 1
    val willFlag: Boolean get() = (raw.toInt() shr 2) and 1 == 1
    val willQosBit1: Boolean get() = (raw.toInt() shr 3) and 1 == 1
    val willQosBit2: Boolean get() = (raw.toInt() shr 4) and 1 == 1
    val willQos: Int get() = (raw.toInt() shr 3) and 3
    val willRetain: Boolean get() = (raw.toInt() shr 5) and 1 == 1
    val passwordFlag: Boolean get() = (raw.toInt() shr 6) and 1 == 1
    val usernameFlag: Boolean get() = (raw.toInt() shr 7) and 1 == 1

    companion object {
        fun from(
            cleanStart: Boolean = false,
            willFlag: Boolean = false,
            willQos: QualityOfService = QualityOfService.AT_MOST_ONCE,
            willRetain: Boolean = false,
            hasPassword: Boolean = false,
            hasUserName: Boolean = false,
        ): ConnectFlagsV5 {
            val raw =
                (if (hasUserName) 0b10000000 else 0) or
                    (if (hasPassword) 0b1000000 else 0) or
                    (if (willRetain) 0b100000 else 0) or
                    (willQos.integerValue.toInt() shl 3) or
                    (if (willFlag) 0b100 else 0) or
                    (if (cleanStart) 0b10 else 0)
            return ConnectFlagsV5(raw.toUByte())
        }
    }
}

// Migration target. Each nested variant becomes the canonical replacement for one of the
// hand-written legacy classes (PingRequest, PublishAcknowledgment, etc.). `ControlPacketV5` extends
// `ControlPacketV5` so legacy callers using the parent type continue to work; legacy
// implementers in this package coexist on the sealed tree until their slice migrates.
//
// The processor processes only this `ControlPacketV5` declaration (the parent `ControlPacketV5`
// has no `@ProtocolMessage`). All direct members declared here carry `@PacketType` so the
// "every direct subclass needs @PacketType" rule is satisfied. The parent's other,
// un-migrated direct members are invisible to the processor.

@DispatchOn(MqttFixedHeader::class)
@ProtocolMessage
sealed interface ControlPacketV5 : com.ditchoom.mqtt.controlpacket.ControlPacket {
    override val mqttVersion: Byte get() = 5
    override val controlPacketFactory: com.ditchoom.mqtt.controlpacket.ControlPacketFactory get() = ControlPacketV5Factory

    companion object {
        fun from(buffer: ReadBuffer): ControlPacketV5 {
            val byte1 = buffer.readUnsignedByte()
            val remainingLength = with(com.ditchoom.mqtt.controlpacket.ControlPacket.Companion) { buffer.readVariableByteInteger() }
            val remainingBuffer =
                if (remainingLength > 0) {
                    buffer.readBytes(remainingLength)
                } else {
                    ReadBuffer.EMPTY_BUFFER
                }
            return from(remainingBuffer, byte1, remainingLength)
        }

        /**
         * Decode a v5 control packet from the body buffer (after byte1 + RL have been
         * stripped). For PUBLISH (type 3) the routing populates the discriminator + binary-data
         * decode keys before delegating to [ControlPacketV5PublishCodec]; other packet types route
         * directly to their generated codec.
         */
        fun from(
            buffer: ReadBuffer,
            byte1: UByte,
            remainingLength: Int,
        ): ControlPacketV5 {
            val packetValue = (byte1.toUInt() shr 4).toInt()
            return when (packetValue) {
                0 -> throw MalformedPacketException("Reserved packet type 0 is not permitted")
                3 -> {
                    val header = MqttFixedHeader(byte1)
                    if (header.publishQos == 3) {
                        throw MalformedPacketException(
                            "[MQTT-3.3.1-4] PUBLISH MUST NOT have both QoS bits set to 1.",
                        )
                    }
                    val ctx =
                        publishPropertyDecodeContext()
                            .with(ControlPacketV5Codec.DiscriminatorKey, header)
                    ControlPacketV5PublishCodec.decode(buffer, ctx) { slice -> slice }
                }
                in migratedPacketTypes -> decodeMigrated(buffer, byte1, packetValue, remainingLength)
                else -> throw MalformedPacketException(
                    "Invalid MQTT Control Packet Type: $packetValue Should be in range between 0 and 15 inclusive",
                )
            }
        }

        private val migratedPacketTypes = setOf(1, 2, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15)

        private fun decodeMigrated(
            buffer: ReadBuffer,
            byte1: UByte,
            packetValue: Int,
            remainingLength: Int,
        ): ControlPacketV5 {
            val flags = byte1.toInt() and 0x0F
            val expectedFlags = if (packetValue == 6 || packetValue == 8 || packetValue == 10) 0x02 else 0x00
            if (flags != expectedFlags) {
                throw MalformedPacketException(
                    "Reserved fixed-header flags for packet type $packetValue must be 0x${
                        expectedFlags.toString(16)
                    }, got 0x${flags.toString(16)}",
                )
            }
            val ctx =
                com.ditchoom.buffer.codec.DecodeContext.Empty
                    .with(ControlPacketV5Codec.DiscriminatorKey, MqttFixedHeader(byte1))
            return when (packetValue) {
                1 ->
                    ControlPacketV5ConnectCodec.decode<ReadBuffer?>(buffer) { slice ->
                        if (slice.remaining() > 0) slice else null
                    }
                2 -> ControlPacketV5ConnAckCodec.decode(buffer, ctx)
                4 -> ControlPacketV5PubAckCodec.decode(buffer, ctx)
                5 -> ControlPacketV5PubRecCodec.decode(buffer, ctx)
                6 -> ControlPacketV5PubRelCodec.decode(buffer, ctx)
                7 -> ControlPacketV5PubCompCodec.decode(buffer, ctx)
                8 -> ControlPacketV5SubscribeCodec.decode(buffer, ctx)
                9 -> ControlPacketV5SubAckCodec.decode(buffer, ctx)
                10 -> ControlPacketV5UnsubscribeCodec.decode(buffer, ctx)
                11 -> ControlPacketV5UnsubAckCodec.decode(buffer, ctx)
                12 ->
                    if (remainingLength != 0) {
                        throw MalformedPacketException(
                            "PINGREQ remaining length must be 0, got $remainingLength",
                        )
                    } else {
                        PingReq
                    }
                13 ->
                    if (remainingLength != 0) {
                        throw MalformedPacketException(
                            "PINGRESP remaining length must be 0, got $remainingLength",
                        )
                    } else {
                        PingResp
                    }
                14 -> ControlPacketV5DisconnectCodec.decode(buffer, ctx)
                15 -> ControlPacketV5AuthCodec.decode(buffer, ctx)
                else -> throw IllegalStateException("Unreachable: $packetValue not in migratedPacketTypes")
            }
        }
    }

    @PacketType(value = 1, wire = 0x10)
    @ProtocolMessage
    data class Connect<@Payload WP>(
        @LengthPrefixed override val protocolName: String,
        val protocolLevel: UByte,
        val connectFlags: ConnectFlagsV5,
        val keepAlive: UShort,
        @LengthPrefixed(LengthPrefix.Varint, maxBytes = 4) val properties: List<MqttProperty> = emptyList(),
        @LengthPrefixed val clientId: String,
        @WhenTrue(
            "connectFlags.willFlag",
        ) @LengthPrefixed(LengthPrefix.Varint, maxBytes = 4) val willProperties: List<MqttProperty>? = null,
        // Wire-shape will fields. Names are distinct from IConnectionRequest's typed
        // accessors (willTopic: TopicName?, willPayload: ReadBuffer?) which are derived
        // via `will: WillConfig`. The codec processor uses these names verbatim for the
        // wire serialization; the typed accessors below convert at the API boundary.
        @WhenTrue("connectFlags.willFlag") @LengthPrefixed val willTopicString: String? = null,
        @WhenTrue("connectFlags.willFlag") @LengthPrefixed val willPayloadValue: WP? = null,
        @WhenTrue("connectFlags.usernameFlag") @LengthPrefixed val username: String? = null,
        @WhenTrue("connectFlags.passwordFlag") @LengthPrefixed override val password: String? = null,
    ) : ControlPacketV5,
        IConnectionRequest {
        init {
            if (connectFlags.reserved) {
                throw MalformedPacketException(
                    "Reserved flag in CONNECT Variable Header is set incorrectly to 1 (§3.1.2.3)",
                )
            }
            // §3.1.2-12: Will QoS = 3 is malformed.
            if (connectFlags.willQos == 3) {
                throw MalformedPacketException("Will QoS = 3 is a Malformed Packet (§3.1.2-12)")
            }
            // §3.1.2-11: If Will Flag = 0, Will QoS MUST be 0.
            if (!connectFlags.willFlag && connectFlags.willQos != 0) {
                throw MalformedPacketException("[MQTT-3.1.2-11] Will QoS must be 0 when Will Flag is 0")
            }
            // §3.1.2-13: If Will Flag = 0, Will Retain MUST be 0.
            if (!connectFlags.willFlag && connectFlags.willRetain) {
                throw MalformedPacketException("[MQTT-3.1.2-13] Will Retain must be 0 when Will Flag is 0")
            }
        }

        override val controlPacketValue: Byte get() = 1
        override val direction: DirectionOfFlow get() = DirectionOfFlow.CLIENT_TO_SERVER

        // ── IConnectionRequest typed accessors ──
        override val clientIdentifier: String get() = clientId
        override val keepAliveTimeoutSeconds: UShort get() = keepAlive
        override val protocolVersion: Int get() = protocolLevel.toInt()
        override val cleanStart: Boolean get() = connectFlags.cleanStart
        override val hasUserName: Boolean get() = connectFlags.usernameFlag
        override val hasPassword: Boolean get() = connectFlags.passwordFlag
        override val userName: String? get() = username

        val typedProperties: ConnectProperties get() = ConnectProperties.from(properties)
        val typedWillProperties: ConnectWillProperties?
            get() = if (willProperties != null) ConnectWillProperties.from(willProperties) else null

        override val sessionExpiryIntervalSeconds: ULong? get() = typedProperties.sessionExpiryIntervalSeconds
        override val receiveMaximum: UShort
            get() = typedProperties.receiveMaximum?.toUShort() ?: UShort.MAX_VALUE
        override val maxPacketSize: ULong
            get() = typedProperties.maximumPacketSize ?: ULong.MAX_VALUE
        override val topicAliasMax: UShort? get() = typedProperties.topicAliasMaximum?.toUShort()
        override val userProperty: List<Pair<String, String>> get() = typedProperties.userProperty

        override val will: WillConfig
            get() =
                if (
                    connectFlags.willFlag &&
                    willTopicString != null &&
                    willPayloadValue is ReadBuffer
                ) {
                    WillConfig.Enabled(
                        TopicName.fromOrThrow(willTopicString),
                        willPayloadValue as ReadBuffer,
                        QualityOfService.fromBooleans(connectFlags.willQosBit2, connectFlags.willQosBit1),
                        connectFlags.willRetain,
                    )
                } else {
                    WillConfig.Disabled
                }

        override val payloadFormatIndicator: Boolean
            get() = typedWillProperties?.payloadFormatIndicator ?: false
        override val messageExpiryIntervalSeconds: Long?
            get() = typedWillProperties?.messageExpiryIntervalSeconds
        override val contentType: String? get() = typedWillProperties?.contentType
        override val responseTopic: TopicName? get() = typedWillProperties?.responseTopic
        override val correlationData: ReadBuffer? get() = typedWillProperties?.correlationData
        override val willDelayIntervalSeconds: Long
            get() = typedWillProperties?.willDelayIntervalSeconds ?: 0L

        override fun validate(): MqttWarning? {
            if (connectFlags.usernameFlag && username == null) {
                return MqttWarning("[MQTT-3.1.2-17]", "Username flag set but no Username present")
            }
            if (!connectFlags.usernameFlag && username != null) {
                return MqttWarning("[MQTT-3.1.2-16]", "Username present but Username flag not set")
            }
            if (connectFlags.passwordFlag && password == null) {
                return MqttWarning("[MQTT-3.1.2-19]", "Password flag set but no Password present")
            }
            if (!connectFlags.passwordFlag && password != null) {
                return MqttWarning("[MQTT-3.1.2-18]", "Password present but Password flag not set")
            }
            return null
        }

        override fun encodeBody(writeBuffer: WriteBuffer) {
            // ControlPacketV5ConnectCodec.encode is generic; production always uses ReadBuffer payloads.
            @Suppress("UNCHECKED_CAST")
            ControlPacketV5ConnectCodec.encode(writeBuffer, this as Connect<ReadBuffer?>) { buf, wp ->
                if (wp != null) buf.write(wp)
            }
        }

        @Suppress("UNCHECKED_CAST")
        override fun remainingLength(): Int =
            ControlPacketV5ConnectCodec.wireSize(this as Connect<ReadBuffer?>) { wp ->
                (wp as? ReadBuffer)?.remaining() ?: 0
            }

        companion object {
            /** High-level convenience factory taking typed shapes. */
            fun create(
                clientId: String,
                keepAliveSeconds: Int = UShort.MAX_VALUE.toInt(),
                cleanStart: Boolean = false,
                userName: String? = null,
                password: String? = null,
                will: WillConfig = WillConfig.Disabled,
                protocolName: String = "MQTT",
                protocolVersion: UByte = 5u,
                props: ConnectProperties = ConnectProperties(),
                willProperties: ConnectWillProperties? =
                    if (will is WillConfig.Enabled) ConnectWillProperties() else null,
            ): Connect<ReadBuffer?> {
                val willEnabled = will as? WillConfig.Enabled
                val flags =
                    ConnectFlagsV5.from(
                        cleanStart = cleanStart,
                        willFlag = will is WillConfig.Enabled,
                        willQos = willEnabled?.qos ?: QualityOfService.AT_MOST_ONCE,
                        willRetain = willEnabled?.retain ?: false,
                        hasPassword = password != null,
                        hasUserName = userName != null,
                    )
                return Connect(
                    protocolName = protocolName,
                    protocolLevel = protocolVersion,
                    connectFlags = flags,
                    keepAlive = keepAliveSeconds.toUShort(),
                    properties = props.props,
                    clientId = clientId,
                    willProperties =
                        if (will is WillConfig.Enabled) {
                            (willProperties ?: ConnectWillProperties()).props
                        } else {
                            null
                        },
                    willTopicString = willEnabled?.topic?.toString(),
                    willPayloadValue = willEnabled?.payload,
                    username = userName,
                    password = password,
                )
            }
        }
    }

    @PacketType(value = 2, wire = 0x20)
    @ProtocolMessage
    data class ConnAck(
        val acknowledgeFlags: UByte,
        val connectReasonCode: UByte,
        @LengthPrefixed(LengthPrefix.Varint, maxBytes = 4) val properties: List<MqttProperty> = emptyList(),
    ) : ControlPacketV5,
        IConnectionAcknowledgment {
        constructor(
            sessionPresent: Boolean = false,
            connectReason: ReasonCode = SUCCESS,
            properties: ConnAckProperties = ConnAckProperties(),
        ) : this(
            acknowledgeFlags = (if (sessionPresent) 1u else 0u).toUByte(),
            connectReasonCode = connectReason.byte,
            properties = properties.props,
        )

        init {
            require((acknowledgeFlags.toInt() and 0xFE) == 0) {
                "CONNACK Acknowledge Flags reserved bits 1-7 must be 0 (§3.2.2.1), got 0x${acknowledgeFlags.toString(16)}"
            }
            require(connectReasonCode in connackConnectReason) {
                "Invalid CONNACK reason code 0x${connectReasonCode.toString(16)}"
            }
            // §3.2.2-6: If a Server sends a CONNACK packet containing a non-zero Reason Code it MUST set Session Present to 0
            if (connectReasonCode != SUCCESS.byte) {
                require((acknowledgeFlags.toInt() and 0x01) == 0) {
                    "CONNACK with non-success reason code MUST have sessionPresent=0 (§3.2.2-6)"
                }
            }
        }

        override val controlPacketValue: Byte get() = 2
        override val direction: DirectionOfFlow get() = DirectionOfFlow.SERVER_TO_CLIENT

        override val sessionPresent: Boolean get() = (acknowledgeFlags.toInt() and 0x01) == 1
        val connectReason: ReasonCode get() = connackConnectReason[connectReasonCode]!!
        val typedProperties: ConnAckProperties get() = ConnAckProperties.from(properties)

        override val isSuccessful: Boolean get() = connectReasonCode == SUCCESS.byte
        override val connectionReason: String get() = connectReason.name
        override val sessionExpiryInterval: ULong
            get() = typedProperties.sessionExpiryIntervalSeconds ?: 0uL
        override val assignedClientIdentifier: String?
            get() = typedProperties.assignedClientIdentifier
        override val maxPacketSize: ULong
            get() = typedProperties.maximumPacketSize ?: ULong.MAX_VALUE
        override val maximumQos: com.ditchoom.mqtt.controlpacket.QualityOfService
            get() = typedProperties.maximumQos
        override val receiveMaximum: Int get() = typedProperties.receiveMaximum
        override val serverKeepAlive: Int get() = typedProperties.serverKeepAlive ?: -1

        override fun encodeBody(writeBuffer: WriteBuffer) = ControlPacketV5ConnAckCodec.encode(writeBuffer, this)

        override fun remainingLength(): Int = ControlPacketV5ConnAckCodec.wireSize(this)
    }

    /**
     * MQTT 5.0 PUBLISH (§3.3). Wire-shape data class; typed accessors on the [PublishMessage]
     * interface delegate to fields decoded from the fixed-header byte (`header.publishDup`,
     * `.publishQos`, `.publishRetain`, `.publishHasPacketIdentifier`).
     *
     * The `<@Payload P>` type parameter enables zero-copy slice forwarding on decode and
     * caller-supplied encoding on encode. Production callers use `Publish<ReadBuffer>`;
     * higher-level helpers eagerly encode typed payloads to a `ReadBuffer` at the API
     * boundary so the wire-encoding side is monomorphic.
     */
    @PacketType(value = 3)
    @ProtocolMessage
    data class Publish<@Payload P>(
        val header: MqttFixedHeader,
        @LengthPrefixed val topicName: String,
        @WhenTrue("header.publishHasPacketIdentifier") val packetId: UShort? = null,
        @LengthPrefixed(LengthPrefix.Varint, maxBytes = 4) val properties: List<MqttProperty> = emptyList(),
        @RemainingBytes val payload: P,
    ) : ControlPacketV5,
        PublishMessage {
        init {
            // §3.3.1-2: Reserved QoS = 3 is malformed.
            if (header.publishQos == 3) {
                throw MalformedPacketException(
                    "[MQTT-3.3.1-4] PUBLISH MUST NOT have both QoS bits set to 1.",
                )
            }
        }

        override val controlPacketValue: Byte get() = 3
        override val direction: DirectionOfFlow get() = DirectionOfFlow.BIDIRECTIONAL
        override val flags: Byte get() = (header.raw.toInt() and 0x0F).toByte()

        // ── PublishMessage typed accessors (derived from `header`) ──
        override val topic: TopicName get() = TopicName.fromOrThrow(topicName)
        override val qualityOfService: QualityOfService
            get() =
                QualityOfService.fromBooleans(
                    bit2 = header.publishQos and 0b10 == 0b10,
                    bit1 = header.publishQos and 0b01 == 0b01,
                )
        override val dup: Boolean get() = header.publishDup
        override val retain: Boolean get() = header.publishRetain
        override val packetIdentifier: Int get() = packetId?.toInt() ?: NO_PACKET_ID

        override fun rawPayload(): ReadBuffer? = payload as? ReadBuffer

        /** Typed view of the variable-header properties (§3.3.2.3). */
        val typedProperties: PublishProperties get() = PublishProperties.from(properties)

        // Both encodeBody and remainingLength stay hand-rolled because PublishProperties
        // contains payload-bearing property variants (CorrelationData, AuthData). The
        // generated codec's inline-prefix encode and wireSize for properties both call
        // MqttPropertyCodec.wireSize(it) which throws for payload variants — they need
        // their own payloadSize lambda. Until the codec processor emits nested sealed
        // wireSize calls via wireSizeFromContext (so the registered SizeKeys flow
        // through), we keep mqttPropertiesSize + writeVariableByteIntegerLengthPrefixed
        // which know each property subtype's size explicitly.
        override fun encodeBody(writeBuffer: WriteBuffer) {
            val ctx = publishPropertyEncodeContext()
            writeBuffer.writeLengthPrefixedUtf8String(topicName)
            if (header.publishHasPacketIdentifier) {
                writeBuffer.writeUShort(packetId!!)
            }
            writeBuffer.writeVariableByteIntegerLengthPrefixed(maxBytes = 4, fieldName = "properties") { buf ->
                properties.forEach { MqttPropertyCodec.encode(buf, it, ctx) }
            }
            (payload as? ReadBuffer)?.let { writeBuffer.write(it) }
        }

        override fun remainingLength(): Int {
            var size = UShort.SIZE_BYTES + topicName.utf8Length()
            if (header.publishHasPacketIdentifier) size += UShort.SIZE_BYTES
            val propsSize = mqttPropertiesSize(properties)
            size += variableByteSize(propsSize) + propsSize
            size += (payload as? ReadBuffer)?.remaining() ?: 0
            return size
        }

        override fun expectedResponse(
            reasonCode: ReasonCode,
            reasonString: String?,
            userProperty: List<Pair<String, String>>,
        ): com.ditchoom.mqtt.controlpacket.ControlPacket? =
            when (qualityOfService) {
                QualityOfService.AT_LEAST_ONCE ->
                    PubAck(packetIdentifier, reasonCode, reasonString, userProperty)
                QualityOfService.EXACTLY_ONCE ->
                    PubRec(packetIdentifier, reasonCode, reasonString, userProperty)
                else -> null
            }

        override fun setDupFlagNewPubMessage(): PublishMessage {
            val rawByte = header.raw.toInt()
            return if (qualityOfService == QualityOfService.AT_MOST_ONCE && dup) {
                copy(header = MqttFixedHeader((rawByte and 0xF7).toUByte())) // clear DUP
            } else if (qualityOfService != QualityOfService.AT_MOST_ONCE && !dup) {
                copy(header = MqttFixedHeader((rawByte or 0x08).toUByte())) // set DUP
            } else {
                this
            }
        }

        override fun maybeCopyWithNewPacketIdentifier(packetIdentifier: Int): PublishMessage =
            when (qualityOfService) {
                QualityOfService.AT_MOST_ONCE -> this
                else -> copy(packetId = packetIdentifier.toUShort())
            }

        override fun validate(): MalformedPacketException? {
            val hasPid = packetId != null
            if (qualityOfService == QualityOfService.AT_MOST_ONCE && hasPid) {
                return MalformedPacketException(
                    "[MQTT-2.3.1-1] PUBLISH at QoS 0 MUST NOT contain a Packet Identifier.",
                )
            } else if (qualityOfService.isGreaterThan(QualityOfService.AT_MOST_ONCE) && !hasPid) {
                return MalformedPacketException(
                    "[MQTT-2.3.1-5] PUBLISH at QoS > 0 MUST contain a Packet Identifier.",
                )
            }
            return null
        }

        companion object {
            /**
             * Zero-copy raw-payload factory. Returns `Publish<ReadBuffer>` matching the
             * wire-decode shape; pair with [ControlPacketV5PublishCodec.encode] for sending.
             */
            fun ofRaw(
                topic: TopicName,
                qos: QualityOfService = QualityOfService.AT_MOST_ONCE,
                payload: ReadBuffer? = null,
                dup: Boolean = false,
                retain: Boolean = false,
                packetIdentifier: Int = NO_PACKET_ID,
                properties: PublishProperties = PublishProperties(),
            ): Publish<ReadBuffer> {
                val header = MqttFixedHeader(makePublishHeaderByte(dup, qos, retain))
                val pid =
                    if (qos == QualityOfService.AT_MOST_ONCE || packetIdentifier == NO_PACKET_ID) {
                        null
                    } else {
                        packetIdentifier.toUShort()
                    }
                return Publish(
                    header = header,
                    topicName = topic.toString(),
                    packetId = pid,
                    properties = properties.props,
                    payload = payload ?: ReadBuffer.EMPTY_BUFFER,
                )
            }

            /**
             * Eagerly-encoded typed payload factory. Invokes [encodePayload] once on a
             * `GrowableWriteBuffer` and stores the resulting `ReadBuffer`; the message is
             * monomorphic in `Publish<ReadBuffer>` after construction.
             */
            fun <P> ofTyped(
                topic: TopicName,
                qos: QualityOfService,
                payload: P,
                encodePayload: WriteBuffer.(P) -> Unit,
                dup: Boolean = false,
                retain: Boolean = false,
                packetIdentifier: Int = NO_PACKET_ID,
                properties: PublishProperties = PublishProperties(),
            ): Publish<ReadBuffer> = ofRaw(topic, qos, eagerEncode(payload, encodePayload), dup, retain, packetIdentifier, properties)

            private fun <P> eagerEncode(
                value: P,
                encodePayload: WriteBuffer.(P) -> Unit,
            ): ReadBuffer = com.ditchoom.buffer.codec.encodeWithGrowth { it.encodePayload(value) }

            private fun makePublishHeaderByte(
                dup: Boolean,
                qos: QualityOfService,
                retain: Boolean,
            ): UByte {
                val type = 3 shl 4
                val dupBit = if (dup) 0x08 else 0
                val qosBits = qos.integerValue.toInt() shl 1
                val retainBit = if (retain) 0x01 else 0
                return (type or dupBit or qosBits or retainBit).toUByte()
            }
        }
    }

    @PacketType(value = 4, wire = 0x40)
    @ProtocolMessage
    data class PubAck(
        val packetId: UShort,
        @WhenRemaining(1) val reasonCode: UByte? = null,
        @WhenRemaining(1) @LengthPrefixed(LengthPrefix.Varint, maxBytes = 4) val properties: List<MqttProperty>? = null,
    ) : ControlPacketV5,
        IPublishAcknowledgment {
        constructor(
            packetIdentifier: Int,
            reasonCode: ReasonCode = SUCCESS,
            reasonString: String? = null,
            userProperty: List<Pair<String, String>> = emptyList(),
        ) : this(
            packetId = packetIdentifier.toUShort(),
            reasonCode = collapseReasonCode(reasonCode, reasonString, userProperty),
            properties = ackProps(reasonString, userProperty, forceEmpty = reasonCode != SUCCESS),
        )

        init {
            require(properties == null || reasonCode != null) {
                "PUBACK properties cannot be present without a reason code"
            }
            val rc = reasonCode ?: SUCCESS.byte
            require(rc in pubAckValidReasonCodes) {
                "Invalid PUBACK reason code $rc"
            }
        }

        override val controlPacketValue: Byte get() = 4
        override val direction: DirectionOfFlow get() = DirectionOfFlow.BIDIRECTIONAL
        override val packetIdentifier: Int get() = packetId.toInt()

        override fun encodeBody(writeBuffer: WriteBuffer) = ControlPacketV5PubAckCodec.encode(writeBuffer, this)

        override fun remainingLength() = ControlPacketV5PubAckCodec.wireSize(this)
    }

    @PacketType(value = 5, wire = 0x50)
    @ProtocolMessage
    data class PubRec(
        val packetId: UShort,
        @WhenRemaining(1) val reasonCode: UByte? = null,
        @WhenRemaining(1) @LengthPrefixed(LengthPrefix.Varint, maxBytes = 4) val properties: List<MqttProperty>? = null,
    ) : ControlPacketV5,
        IPublishReceived {
        constructor(
            packetIdentifier: Int,
            reasonCode: ReasonCode = SUCCESS,
            reasonString: String? = null,
            userProperty: List<Pair<String, String>> = emptyList(),
        ) : this(
            packetId = packetIdentifier.toUShort(),
            reasonCode = collapseReasonCode(reasonCode, reasonString, userProperty),
            properties = ackProps(reasonString, userProperty, forceEmpty = reasonCode != SUCCESS),
        )

        init {
            require(properties == null || reasonCode != null) {
                "PUBREC properties cannot be present without a reason code"
            }
            val rc = reasonCode ?: SUCCESS.byte
            require(rc in pubRecValidReasonCodes) {
                "Invalid PUBREC reason code $rc"
            }
        }

        override val controlPacketValue: Byte get() = 5
        override val direction: DirectionOfFlow get() = DirectionOfFlow.BIDIRECTIONAL
        override val packetIdentifier: Int get() = packetId.toInt()

        override fun encodeBody(writeBuffer: WriteBuffer) = ControlPacketV5PubRecCodec.encode(writeBuffer, this)

        override fun remainingLength() = ControlPacketV5PubRecCodec.wireSize(this)

        override fun expectedResponse(
            reasonCode: ReasonCode,
            reasonString: String?,
            userProperty: List<Pair<String, String>>,
        ): IPublishRelease = PubRel(packetIdentifier, reasonCode, reasonString, userProperty)
    }

    @PacketType(value = 6, wire = 0x62)
    @ProtocolMessage
    data class PubRel(
        val packetId: UShort,
        @WhenRemaining(1) val reasonCode: UByte? = null,
        @WhenRemaining(1) @LengthPrefixed(LengthPrefix.Varint, maxBytes = 4) val properties: List<MqttProperty>? = null,
    ) : ControlPacketV5,
        IPublishRelease {
        constructor(
            packetIdentifier: Int,
            reasonCode: ReasonCode = SUCCESS,
            reasonString: String? = null,
            userProperty: List<Pair<String, String>> = emptyList(),
        ) : this(
            packetId = packetIdentifier.toUShort(),
            reasonCode = collapseReasonCode(reasonCode, reasonString, userProperty),
            properties = ackProps(reasonString, userProperty, forceEmpty = reasonCode != SUCCESS),
        )

        init {
            require(properties == null || reasonCode != null) {
                "PUBREL properties cannot be present without a reason code"
            }
            val rc = reasonCode ?: SUCCESS.byte
            require(rc in pubRelValidReasonCodes) {
                "Invalid PUBREL reason code $rc"
            }
        }

        override val controlPacketValue: Byte get() = 6
        override val direction: DirectionOfFlow get() = DirectionOfFlow.BIDIRECTIONAL
        override val flags: Byte get() = 0b10
        override val packetIdentifier: Int get() = packetId.toInt()

        override fun encodeBody(writeBuffer: WriteBuffer) = ControlPacketV5PubRelCodec.encode(writeBuffer, this)

        override fun remainingLength() = ControlPacketV5PubRelCodec.wireSize(this)

        override fun expectedResponse(
            reasonCode: ReasonCode,
            reasonString: String?,
            userProperty: List<Pair<String, String>>,
        ): IPublishComplete = PubComp(packetIdentifier, reasonCode, reasonString, userProperty)
    }

    @PacketType(value = 7, wire = 0x70)
    @ProtocolMessage
    data class PubComp(
        val packetId: UShort,
        @WhenRemaining(1) val reasonCode: UByte? = null,
        @WhenRemaining(1) @LengthPrefixed(LengthPrefix.Varint, maxBytes = 4) val properties: List<MqttProperty>? = null,
    ) : ControlPacketV5,
        IPublishComplete {
        constructor(packetIdentifier: UShort, reasonCode: ReasonCode = SUCCESS) :
            this(
                packetId = packetIdentifier,
                reasonCode = if (reasonCode == SUCCESS) null else reasonCode.byte,
                properties = null,
            )
        constructor(
            packetIdentifier: Int,
            reasonCode: ReasonCode = SUCCESS,
            reasonString: String? = null,
            userProperty: List<Pair<String, String>> = emptyList(),
        ) : this(
            packetId = packetIdentifier.toUShort(),
            reasonCode = collapseReasonCode(reasonCode, reasonString, userProperty),
            properties = ackProps(reasonString, userProperty, forceEmpty = reasonCode != SUCCESS),
        )

        init {
            require(properties == null || reasonCode != null) {
                "PUBCOMP properties cannot be present without a reason code"
            }
            val rc = reasonCode ?: SUCCESS.byte
            require(rc in pubCompValidReasonCodes) {
                "Invalid PUBCOMP reason code $rc"
            }
        }

        override val controlPacketValue: Byte get() = 7
        override val direction: DirectionOfFlow get() = DirectionOfFlow.BIDIRECTIONAL
        override val packetIdentifier: Int get() = packetId.toInt()

        override fun encodeBody(writeBuffer: WriteBuffer) = ControlPacketV5PubCompCodec.encode(writeBuffer, this)

        override fun remainingLength() = ControlPacketV5PubCompCodec.wireSize(this)
    }

    @PacketType(value = 12, wire = 0xC0)
    @ProtocolMessage
    data object PingReq :
        ControlPacketV5,
        IPingRequest {
        override val controlPacketValue: Byte get() = 12
        override val direction: DirectionOfFlow get() = DirectionOfFlow.CLIENT_TO_SERVER
    }

    @PacketType(value = 13, wire = 0xD0)
    @ProtocolMessage
    data object PingResp :
        ControlPacketV5,
        IPingResponse {
        override val controlPacketValue: Byte get() = 13
        override val direction: DirectionOfFlow get() = DirectionOfFlow.SERVER_TO_CLIENT
    }

    @PacketType(value = 14, wire = 0xE0)
    @ProtocolMessage
    data class Disconnect(
        @WhenRemaining(1) val reasonCode: UByte? = null,
        @WhenRemaining(1) @LengthPrefixed(LengthPrefix.Varint, maxBytes = 4) val properties: List<MqttProperty>? = null,
    ) : ControlPacketV5,
        IDisconnectNotification {
        constructor(
            reasonCode: ReasonCode = NORMAL_DISCONNECTION,
            sessionExpiryIntervalSeconds: ULong? = null,
            reasonString: String? = null,
            userProperty: List<Pair<String, String>> = emptyList(),
            serverReference: String? = null,
        ) : this(
            reasonCode =
                collapseDisconnectReasonCode(
                    reasonCode,
                    sessionExpiryIntervalSeconds,
                    reasonString,
                    userProperty,
                    serverReference,
                ),
            properties =
                disconnectProps(
                    sessionExpiryIntervalSeconds,
                    reasonString,
                    userProperty,
                    serverReference,
                    forceEmpty = reasonCode != NORMAL_DISCONNECTION,
                ),
        )

        init {
            require(properties == null || reasonCode != null) {
                "DISCONNECT properties cannot be present without a reason code"
            }
            val rc = reasonCode ?: NORMAL_DISCONNECTION.byte
            require(rc in disconnectValidReasonCodes) {
                "Invalid DISCONNECT reason code $rc"
            }
        }

        override val controlPacketValue: Byte get() = 14
        override val direction: DirectionOfFlow get() = DirectionOfFlow.BIDIRECTIONAL

        override fun encodeBody(writeBuffer: WriteBuffer) = ControlPacketV5DisconnectCodec.encode(writeBuffer, this)

        override fun remainingLength() = ControlPacketV5DisconnectCodec.wireSize(this)
    }

    @PacketType(value = 15, wire = 0xF0)
    @ProtocolMessage
    data class Auth(
        @WhenRemaining(1) val reasonCode: UByte? = null,
        @WhenRemaining(1) @LengthPrefixed(LengthPrefix.Varint, maxBytes = 4) val properties: List<MqttProperty>? = null,
    ) : ControlPacketV5 {
        constructor(
            reasonCode: ReasonCode = SUCCESS,
            reasonString: String? = null,
            userProperty: List<Pair<String, String>> = emptyList(),
        ) : this(
            reasonCode =
                if (reasonCode == SUCCESS && reasonString == null && userProperty.isEmpty()) {
                    null
                } else {
                    reasonCode.byte
                },
            properties = ackProps(reasonString, userProperty, forceEmpty = reasonCode != SUCCESS),
        )

        init {
            require(properties == null || reasonCode != null) {
                "AUTH properties cannot be present without a reason code"
            }
            val rc = reasonCode ?: SUCCESS.byte
            require(rc in authValidReasonCodes) {
                "Invalid AUTH reason code $rc"
            }
        }

        override val controlPacketValue: Byte get() = 15
        override val direction: DirectionOfFlow get() = DirectionOfFlow.BIDIRECTIONAL

        override fun encodeBody(writeBuffer: WriteBuffer) = ControlPacketV5AuthCodec.encode(writeBuffer, this)

        override fun remainingLength() = ControlPacketV5AuthCodec.wireSize(this)
    }

    @PacketType(value = 8, wire = 0x82)
    @ProtocolMessage
    data class Subscribe(
        val packetId: UShort,
        @LengthPrefixed(LengthPrefix.Varint, maxBytes = 4) val properties: List<MqttProperty> = emptyList(),
        @RemainingBytes val subscriptionEntries: List<SubscriptionV5Entry>,
    ) : ControlPacketV5,
        ISubscribeRequest {
        constructor(
            packetIdentifier: UShort,
            topic: String,
            qos: QualityOfService,
            noLocal: Boolean = false,
            retainAsPublished: Boolean = false,
            retainHandling: RetainHandling = SEND_RETAINED_MESSAGES_AT_TIME_OF_SUBSCRIBE,
            reasonString: String? = null,
            userProperty: List<Pair<String, String>> = emptyList(),
        ) : this(
            packetIdentifier,
            setOf(Subscription.from(topic, qos, noLocal, retainAsPublished, retainHandling)),
            reasonString,
            userProperty,
        )

        constructor(
            packetIdentifier: UShort,
            subscriptions: Set<ISubscription>,
            reasonString: String? = null,
            userProperty: List<Pair<String, String>> = emptyList(),
        ) : this(
            packetId = packetIdentifier,
            properties = ackProps(reasonString, userProperty) ?: emptyList(),
            subscriptionEntries = subscriptions.map(::toSubscriptionEntry),
        )

        constructor(
            packetIdentifier: Int,
            topics: List<TopicFilter>,
            qos: List<QualityOfService>,
            noLocalList: List<Boolean>? = null,
            retainAsPublishedList: List<Boolean>? = null,
            retainHandlingList: List<RetainHandling>? = null,
            reasonString: String? = null,
            userProperty: List<Pair<String, String>> = emptyList(),
        ) : this(
            packetIdentifier.toUShort(),
            Subscription.from(topics, qos, noLocalList, retainAsPublishedList, retainHandlingList),
            reasonString,
            userProperty,
        )

        init {
            require(subscriptionEntries.isNotEmpty()) {
                "SUBSCRIBE payload must contain at least one Topic Filter (Protocol Error §3.8.3)"
            }
            for (entry in subscriptionEntries) {
                val opts = entry.subscriptionOptions.toInt()
                require(opts shr 6 == 0) {
                    "Subscription Options reserved bits 6-7 must be 0, got 0x${opts.toString(16)}"
                }
                val rh = (opts shr 4) and 0x3
                require(rh != 3) { "Retain Handling value 3 is a Protocol Error (§3.8.3.1)" }
                val q = opts and 0x3
                require(q != 3) { "Maximum QoS field value 3 is a Protocol Error (§3.8.3.1)" }
            }
        }

        override val controlPacketValue: Byte get() = 8
        override val direction: DirectionOfFlow get() = DirectionOfFlow.CLIENT_TO_SERVER
        override val flags: Byte get() = 0b10
        override val packetIdentifier: Int get() = packetId.toInt()

        override val subscriptions: Set<ISubscription>
            get() = subscriptionEntries.mapTo(linkedSetOf(), ::fromSubscriptionEntry)

        override fun expectedResponse(): ISubscribeAcknowledgement = SubAck(packetId, ReasonCode.SUCCESS)

        override fun copyWithNewPacketIdentifier(packetIdentifier: Int): ISubscribeRequest = copy(packetId = packetIdentifier.toUShort())

        override fun encodeBody(writeBuffer: WriteBuffer) = ControlPacketV5SubscribeCodec.encode(writeBuffer, this)

        override fun remainingLength(): Int = ControlPacketV5SubscribeCodec.wireSize(this)
    }

    @PacketType(value = 9, wire = 0x90)
    @ProtocolMessage
    data class SubAck(
        val packetId: UShort,
        @LengthPrefixed(LengthPrefix.Varint, maxBytes = 4) val properties: List<MqttProperty> = emptyList(),
        @RemainingBytes val reasonCodeEntries: List<SubAckReasonCodeV5>,
    ) : ControlPacketV5,
        ISubscribeAcknowledgement {
        constructor(
            packetIdentifier: UShort,
            payload: ReasonCode = SUCCESS,
            reasonString: String? = null,
            userProperty: List<Pair<String, String>> = emptyList(),
        ) : this(packetIdentifier, listOf(payload), reasonString, userProperty)

        constructor(
            packetIdentifier: UShort,
            reasonCodes: List<ReasonCode>,
            reasonString: String? = null,
            userProperty: List<Pair<String, String>> = emptyList(),
        ) : this(
            packetId = packetIdentifier,
            properties = ackProps(reasonString, userProperty) ?: emptyList(),
            reasonCodeEntries = reasonCodes.map { SubAckReasonCodeV5(it.byte) },
        )

        constructor(
            packetIdentifier: Int,
            reasonString: String? = null,
            userProperty: List<Pair<String, String>> = emptyList(),
            payload: List<ReasonCode>,
        ) : this(packetIdentifier.toUShort(), payload, reasonString, userProperty)

        init {
            require(reasonCodeEntries.isNotEmpty()) {
                "SUBACK payload must contain at least one Reason Code (Protocol Error §3.9.3)"
            }
            for (entry in reasonCodeEntries) {
                if (entry.raw !in subAckValidReasonCodes) {
                    throw ProtocolError("Invalid SUBACK reason code ${entry.raw}")
                }
            }
        }

        override val controlPacketValue: Byte get() = 9
        override val direction: DirectionOfFlow get() = DirectionOfFlow.SERVER_TO_CLIENT
        override val packetIdentifier: Int get() = packetId.toInt()

        val payload: List<ReasonCode>
            get() = reasonCodeEntries.map { decodeSubAckReasonCode(it.raw) }

        override fun encodeBody(writeBuffer: WriteBuffer) = ControlPacketV5SubAckCodec.encode(writeBuffer, this)

        override fun remainingLength(): Int = ControlPacketV5SubAckCodec.wireSize(this)
    }

    @PacketType(value = 10, wire = 0xA2)
    @ProtocolMessage
    data class Unsubscribe(
        val packetId: UShort,
        @LengthPrefixed(LengthPrefix.Varint, maxBytes = 4) val properties: List<MqttProperty> = emptyList(),
        @RemainingBytes val topicEntries: List<TopicFilterV5Entry>,
    ) : ControlPacketV5,
        IUnsubscribeRequest {
        constructor(
            packetIdentifier: UShort,
            topics: Set<TopicFilter>,
            userProperty: List<Pair<String, String>> = emptyList(),
        ) : this(
            packetId = packetIdentifier,
            properties = ackProps(reasonString = null, userProperty = userProperty) ?: emptyList(),
            topicEntries = topics.map { TopicFilterV5Entry(it.toString()) },
        )

        constructor(
            topics: Set<TopicFilter>,
            userProperty: List<Pair<String, String>> = emptyList(),
        ) : this(packetIdentifier = 0u, topics = topics, userProperty = userProperty)

        constructor(
            topic: String,
            userProperty: List<Pair<String, String>> = emptyList(),
        ) : this(setOf(TopicFilter.fromOrThrow(topic)), userProperty)

        init {
            if (topicEntries.isEmpty()) {
                throw ProtocolError("UNSUBSCRIBE payload must contain at least one Topic Filter (§3.10.3)")
            }
        }

        override val controlPacketValue: Byte get() = 10
        override val direction: DirectionOfFlow get() = DirectionOfFlow.CLIENT_TO_SERVER
        override val flags: Byte get() = 0b10
        override val packetIdentifier: Int get() = packetId.toInt()

        override val topics: Set<TopicFilter>
            get() = topicEntries.mapTo(linkedSetOf()) { TopicFilter.fromOrThrow(it.topicFilter) }

        override fun copyWithNewPacketIdentifier(packetIdentifier: Int): IUnsubscribeRequest = copy(packetId = packetIdentifier.toUShort())

        override fun encodeBody(writeBuffer: WriteBuffer) = ControlPacketV5UnsubscribeCodec.encode(writeBuffer, this)

        override fun remainingLength(): Int = ControlPacketV5UnsubscribeCodec.wireSize(this)
    }

    @PacketType(value = 11, wire = 0xB0)
    @ProtocolMessage
    data class UnsubAck(
        val packetId: UShort,
        @LengthPrefixed(LengthPrefix.Varint, maxBytes = 4) val properties: List<MqttProperty> = emptyList(),
        @RemainingBytes val reasonCodeEntries: List<UnsubAckReasonCodeV5>,
    ) : ControlPacketV5,
        IUnsubscribeAcknowledgment {
        constructor(
            packetIdentifier: Int,
            reasonString: String? = null,
            userProperty: List<Pair<String, String>> = emptyList(),
            reasonCodes: List<ReasonCode> = listOf(SUCCESS),
        ) : this(
            packetId = packetIdentifier.toUShort(),
            properties = ackProps(reasonString, userProperty) ?: emptyList(),
            reasonCodeEntries = reasonCodes.map { UnsubAckReasonCodeV5(it.byte) },
        )

        init {
            if (reasonCodeEntries.isEmpty()) {
                throw ProtocolError("UNSUBACK must contain at least one reason code (§3.11.3)")
            }
            for (entry in reasonCodeEntries) {
                if (entry.raw !in unsubAckValidReasonCodes) {
                    throw ProtocolError("Invalid UNSUBACK reason code ${entry.raw}")
                }
            }
        }

        override val controlPacketValue: Byte get() = 11
        override val direction: DirectionOfFlow get() = DirectionOfFlow.SERVER_TO_CLIENT
        override val packetIdentifier: Int get() = packetId.toInt()

        val reasonCodes: List<ReasonCode>
            get() = reasonCodeEntries.map { decodeUnsubAckReasonCode(it.raw) }

        override fun encodeBody(writeBuffer: WriteBuffer) = ControlPacketV5UnsubAckCodec.encode(writeBuffer, this)

        override fun remainingLength(): Int = ControlPacketV5UnsubAckCodec.wireSize(this)
    }
}

// Backward-compat type aliases. Legacy callers and tests can keep using the long names.
typealias PingRequest = ControlPacketV5.PingReq
typealias PingResponse = ControlPacketV5.PingResp
typealias PublishAcknowledgment = ControlPacketV5.PubAck
typealias PublishReceived = ControlPacketV5.PubRec
typealias PublishRelease = ControlPacketV5.PubRel
typealias PublishComplete = ControlPacketV5.PubComp
typealias DisconnectNotification = ControlPacketV5.Disconnect
typealias AuthenticationExchange = ControlPacketV5.Auth
typealias ConnectionAcknowledgment = ControlPacketV5.ConnAck
typealias ConnectionRequest = ControlPacketV5.Connect<ReadBuffer?>

/**
 * Convenience factory matching the legacy ConnectionRequest() constructor signature.
 * Re-exports as `ConnectionRequest(...)` so existing call sites keep working through the
 * typealias.
 */
@Suppress("FunctionName")
fun ConnectionRequest(
    clientId: String = "",
    keepAliveSeconds: Int = UShort.MAX_VALUE.toInt(),
    cleanStart: Boolean = false,
    userName: String? = null,
    password: String? = null,
    will: WillConfig = WillConfig.Disabled,
    protocolName: String = "MQTT",
    protocolVersion: UByte = 5u,
    props: ConnectProperties = ConnectProperties(),
    willProperties: ConnectWillProperties? =
        if (will is WillConfig.Enabled) ConnectWillProperties() else null,
): ConnectionRequest =
    ControlPacketV5.Connect.create(
        clientId,
        keepAliveSeconds,
        cleanStart,
        userName,
        password,
        will,
        protocolName,
        protocolVersion,
        props,
        willProperties,
    )
typealias SubscribeRequest = ControlPacketV5.Subscribe
typealias SubscribeAcknowledgement = ControlPacketV5.SubAck
typealias UnsubscribeRequest = ControlPacketV5.Unsubscribe
typealias UnsubscribeAcknowledgment = ControlPacketV5.UnsubAck

// Helpers shared by ack-shaped variants. Kept private to this file.

// Typed views over a `Collection<MqttProperty>?` for callers that built the bag from named
// fields and want them back. Returning empty/null when the bag is missing the property keeps
// callers terse.

fun Collection<MqttProperty>?.reasonStringValue(): String? = this?.firstNotNullOfOrNull { (it as? ReasonString)?.value }

fun Collection<MqttProperty>?.userProperties(): List<Pair<String, String>> =
    this?.filterIsInstance<UserProperty>()?.map { it.key to it.value } ?: emptyList()

internal fun ackProps(
    reasonString: String?,
    userProperty: List<Pair<String, String>>,
    forceEmpty: Boolean = false,
): List<MqttProperty>? {
    if (!forceEmpty && reasonString == null && userProperty.isEmpty()) return null
    return buildList {
        if (reasonString != null) add(ReasonString(reasonString))
        for ((k, v) in userProperty) add(UserProperty(k, v))
    }
}

internal fun disconnectProps(
    sessionExpiryIntervalSeconds: ULong?,
    reasonString: String?,
    userProperty: List<Pair<String, String>>,
    serverReference: String?,
    forceEmpty: Boolean = false,
): List<MqttProperty>? {
    if (!forceEmpty &&
        sessionExpiryIntervalSeconds == null &&
        reasonString == null &&
        userProperty.isEmpty() &&
        serverReference == null
    ) {
        return null
    }
    return buildList {
        if (sessionExpiryIntervalSeconds != null) {
            add(SessionExpiryInterval(sessionExpiryIntervalSeconds.toUInt()))
        }
        if (reasonString != null) add(ReasonString(reasonString))
        for ((k, v) in userProperty) add(UserProperty(k, v))
        if (serverReference != null) add(ServerReference(serverReference))
    }
}

internal fun collapseDisconnectReasonCode(
    reasonCode: ReasonCode,
    sessionExpiryIntervalSeconds: ULong?,
    reasonString: String?,
    userProperty: List<Pair<String, String>>,
    serverReference: String?,
): UByte? {
    val canOmit =
        reasonCode == NORMAL_DISCONNECTION &&
            sessionExpiryIntervalSeconds == null &&
            reasonString == null &&
            userProperty.isEmpty() &&
            serverReference == null
    return if (canOmit) null else reasonCode.byte
}

internal fun collapseReasonCode(
    reasonCode: ReasonCode,
    reasonString: String?,
    userProperty: List<Pair<String, String>>,
): UByte? {
    val canOmit = reasonCode == SUCCESS && reasonString == null && userProperty.isEmpty()
    return if (canOmit) null else reasonCode.byte
}

// Spec reason-code validation sets, by byte (the wire representation).

private val pubAckValidReasonCodes: Set<UByte> =
    setOf(
        SUCCESS.byte,
        NO_MATCHING_SUBSCRIBERS.byte,
        UNSPECIFIED_ERROR.byte,
        IMPLEMENTATION_SPECIFIC_ERROR.byte,
        NOT_AUTHORIZED.byte,
        TOPIC_NAME_INVALID.byte,
        PACKET_IDENTIFIER_IN_USE.byte,
        QUOTA_EXCEEDED.byte,
        PAYLOAD_FORMAT_INVALID.byte,
    )

private val pubRecValidReasonCodes: Set<UByte> = pubAckValidReasonCodes

private val pubRelValidReasonCodes: Set<UByte> =
    setOf(
        SUCCESS.byte,
        PACKET_IDENTIFIER_NOT_FOUND.byte,
        UNSPECIFIED_ERROR.byte,
        IMPLEMENTATION_SPECIFIC_ERROR.byte,
        NOT_AUTHORIZED.byte,
    )

private val pubCompValidReasonCodes: Set<UByte> = pubRelValidReasonCodes

private val disconnectValidReasonCodes: Set<UByte> =
    setOf(
        NORMAL_DISCONNECTION.byte,
        DISCONNECT_WITH_WILL_MESSAGE.byte,
        UNSPECIFIED_ERROR.byte,
        MALFORMED_PACKET.byte,
        PROTOCOL_ERROR.byte,
        IMPLEMENTATION_SPECIFIC_ERROR.byte,
        NOT_AUTHORIZED.byte,
        SERVER_BUSY.byte,
        SERVER_SHUTTING_DOWN.byte,
        KEEP_ALIVE_TIMEOUT.byte,
        SESSION_TAKE_OVER.byte,
        TOPIC_FILTER_INVALID.byte,
        TOPIC_NAME_INVALID.byte,
        RECEIVE_MAXIMUM_EXCEEDED.byte,
        TOPIC_ALIAS_INVALID.byte,
        PACKET_TOO_LARGE.byte,
        MESSAGE_RATE_TOO_HIGH.byte,
        QUOTA_EXCEEDED.byte,
        ADMINISTRATIVE_ACTION.byte,
        PAYLOAD_FORMAT_INVALID.byte,
        RETAIN_NOT_SUPPORTED.byte,
        QOS_NOT_SUPPORTED.byte,
        USE_ANOTHER_SERVER.byte,
        SERVER_MOVED.byte,
        SHARED_SUBSCRIPTIONS_NOT_SUPPORTED.byte,
        CONNECTION_RATE_EXCEEDED.byte,
        MAXIMUM_CONNECTION_TIME.byte,
        SUBSCRIPTION_IDENTIFIERS_NOT_SUPPORTED.byte,
        WILDCARD_SUBSCRIPTIONS_NOT_SUPPORTED.byte,
    )

private val authValidReasonCodes: Set<UByte> =
    setOf(
        SUCCESS.byte,
        CONTINUE_AUTHENTICATION.byte,
        REAUTHENTICATE.byte,
    )

internal fun toSubscriptionEntry(sub: ISubscription): SubscriptionV5Entry {
    val qos = sub.maximumQos.integerValue.toInt()
    val nl = (if (sub.noLocal) 1 else 0) shl 2
    val rap = (if (sub.retainAsPublished) 1 else 0) shl 3
    val rh = sub.retainHandling.value.toInt() shl 4
    return SubscriptionV5Entry(sub.topicFilter.toString(), (qos or nl or rap or rh).toUByte())
}

internal fun fromSubscriptionEntry(entry: SubscriptionV5Entry): Subscription {
    val opts = entry.subscriptionOptions.toInt()
    require(opts shr 6 == 0) {
        "Subscription Options reserved bits 6-7 must be 0"
    }
    val rh = (opts shr 4) and 0x3
    val retainHandling =
        when (rh) {
            0 -> SEND_RETAINED_MESSAGES_AT_TIME_OF_SUBSCRIBE
            1 -> SEND_RETAINED_MESSAGES_AT_SUBSCRIBE_ONLY_IF_SUBSCRIBE_DOESNT_EXISTS
            2 -> DO_NOT_SEND_RETAINED_MESSAGES
            else -> throw ProtocolError("Retain Handling Value cannot be set to 3")
        }
    val rap = (opts shr 3) and 0x1 == 1
    val nl = (opts shr 2) and 0x1 == 1
    val qos = QualityOfService.fromBooleans((opts shr 1) and 0x1 == 1, opts and 0x1 == 1)
    return Subscription(TopicFilter.fromOrThrow(entry.topicFilter), qos, nl, rap, retainHandling)
}

internal fun decodeSubAckReasonCode(byte: UByte): ReasonCode =
    when (byte) {
        ReasonCode.GRANTED_QOS_0.byte -> ReasonCode.GRANTED_QOS_0
        ReasonCode.GRANTED_QOS_1.byte -> ReasonCode.GRANTED_QOS_1
        ReasonCode.GRANTED_QOS_2.byte -> ReasonCode.GRANTED_QOS_2
        UNSPECIFIED_ERROR.byte -> UNSPECIFIED_ERROR
        IMPLEMENTATION_SPECIFIC_ERROR.byte -> IMPLEMENTATION_SPECIFIC_ERROR
        NOT_AUTHORIZED.byte -> NOT_AUTHORIZED
        TOPIC_FILTER_INVALID.byte -> TOPIC_FILTER_INVALID
        PACKET_IDENTIFIER_IN_USE.byte -> PACKET_IDENTIFIER_IN_USE
        QUOTA_EXCEEDED.byte -> QUOTA_EXCEEDED
        SHARED_SUBSCRIPTIONS_NOT_SUPPORTED.byte -> SHARED_SUBSCRIPTIONS_NOT_SUPPORTED
        SUBSCRIPTION_IDENTIFIERS_NOT_SUPPORTED.byte -> SUBSCRIPTION_IDENTIFIERS_NOT_SUPPORTED
        WILDCARD_SUBSCRIPTIONS_NOT_SUPPORTED.byte -> WILDCARD_SUBSCRIPTIONS_NOT_SUPPORTED
        else -> throw com.ditchoom.mqtt.MalformedPacketException("Invalid SUBACK reason code $byte")
    }

internal fun decodeUnsubAckReasonCode(byte: UByte): ReasonCode =
    when (byte) {
        SUCCESS.byte -> SUCCESS
        ReasonCode.NO_SUBSCRIPTIONS_EXISTED.byte -> ReasonCode.NO_SUBSCRIPTIONS_EXISTED
        UNSPECIFIED_ERROR.byte -> UNSPECIFIED_ERROR
        IMPLEMENTATION_SPECIFIC_ERROR.byte -> IMPLEMENTATION_SPECIFIC_ERROR
        NOT_AUTHORIZED.byte -> NOT_AUTHORIZED
        TOPIC_FILTER_INVALID.byte -> TOPIC_FILTER_INVALID
        PACKET_IDENTIFIER_IN_USE.byte -> PACKET_IDENTIFIER_IN_USE
        else -> throw com.ditchoom.mqtt.MalformedPacketException("Invalid UNSUBACK reason code $byte")
    }

private val subAckValidReasonCodes: Set<UByte> =
    setOf(
        ReasonCode.GRANTED_QOS_0.byte, // shares 0x00 with SUCCESS
        ReasonCode.GRANTED_QOS_1.byte,
        ReasonCode.GRANTED_QOS_2.byte,
        UNSPECIFIED_ERROR.byte,
        IMPLEMENTATION_SPECIFIC_ERROR.byte,
        NOT_AUTHORIZED.byte,
        TOPIC_FILTER_INVALID.byte,
        PACKET_IDENTIFIER_IN_USE.byte,
        QUOTA_EXCEEDED.byte,
        SHARED_SUBSCRIPTIONS_NOT_SUPPORTED.byte,
        SUBSCRIPTION_IDENTIFIERS_NOT_SUPPORTED.byte,
        WILDCARD_SUBSCRIPTIONS_NOT_SUPPORTED.byte,
    )

private val unsubAckValidReasonCodes: Set<UByte> =
    setOf(
        SUCCESS.byte,
        ReasonCode.NO_SUBSCRIPTIONS_EXISTED.byte,
        UNSPECIFIED_ERROR.byte,
        IMPLEMENTATION_SPECIFIC_ERROR.byte,
        NOT_AUTHORIZED.byte,
        TOPIC_FILTER_INVALID.byte,
        PACKET_IDENTIFIER_IN_USE.byte,
    )

/**
 * Encode context for the two MQTT v5 binary-data property variants — the codec dispatcher
 * reads these lambdas to write `data: ReadBuffer` payloads on the wire (zero-copy slice
 * transfer).
 */
internal fun publishPropertyEncodeContext(): EncodeContext =
    EncodeContext.Empty
        .with(CorrelationDataCodec.DataEncodeKey) { buf, data ->
            val rb = data as ReadBuffer
            rb.position(0)
            buf.write(rb)
        }.with(AuthenticationDataCodec.DataEncodeKey) { buf, data ->
            val rb = data as ReadBuffer
            rb.position(0)
            buf.write(rb)
        }

/**
 * Decode context for the two MQTT v5 binary-data property variants — identity slice
 * passthrough; callers retaining the data past the decode scope must copy explicitly.
 */
internal fun publishPropertyDecodeContext(): com.ditchoom.buffer.codec.DecodeContext =
    com.ditchoom.buffer.codec.DecodeContext.Empty
        .with(CorrelationDataCodec.DataDecodeKey) { slice -> slice }
        .with(AuthenticationDataCodec.DataDecodeKey) { slice -> slice }
