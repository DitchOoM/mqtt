package com.ditchoom.mqtt5.controlpacket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.Payload
import com.ditchoom.buffer.codec.annotations.DispatchOn
import com.ditchoom.buffer.codec.annotations.FramedBy
import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes
import com.ditchoom.buffer.codec.annotations.UseCodec
import com.ditchoom.buffer.codec.annotations.When
import com.ditchoom.mqtt.MalformedPacketException
import com.ditchoom.mqtt.MqttWarning
import com.ditchoom.mqtt.ProtocolError
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
import com.ditchoom.mqtt.controlpacket.MqttFixedHeader
import com.ditchoom.mqtt.controlpacket.MqttRemainingLengthCodec
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
import com.ditchoom.mqtt5.controlpacket.properties.MqttProperty
import com.ditchoom.mqtt5.controlpacket.properties.ReasonString
import com.ditchoom.mqtt5.controlpacket.properties.ServerReference
import com.ditchoom.mqtt5.controlpacket.properties.SessionExpiryInterval
import com.ditchoom.mqtt5.controlpacket.properties.UserProperty
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

@ProtocolMessage
data class SubAckReasonCodeV5(
    val raw: UByte,
)

@ProtocolMessage
data class UnsubAckReasonCodeV5(
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

// ── Sealed root ───────────────────────────────────────────────────────────

/**
 * MQTT 5.0 control-packet sealed tree. Each variant is a `@PacketType`-tagged nested
 * data class; the generated `ControlPacketV5Codec` dispatches on the [MqttFixedHeader]'s
 * top nibble and frames the body via [MqttRemainingLengthCodec] (`@FramedBy after = "header"`).
 *
 * @see https://docs.oasis-open.org/mqtt/mqtt/v5.0/os/mqtt-v5.0-os.html
 */
@DispatchOn(MqttFixedHeader::class)
@FramedBy(MqttRemainingLengthCodec::class, after = "header")
@ProtocolMessage
sealed interface ControlPacketV5<out P : Payload> : com.ditchoom.mqtt.controlpacket.ControlPacket {
    override val mqttVersion: Byte get() = 5
    override val controlPacketFactory: com.ditchoom.mqtt.controlpacket.ControlPacketFactory get() = ControlPacketV5Factory

    // Override the legacy `ControlPacket.serialize(...)` / `packetSize()` (which use the
    // gone encodeBody/remainingLength path) to route through the v5 sealed-tree codec
    // with [OpaquePublishPayload] as the PUBLISH payload carrier. Production callers with
    // typed PUBLISH payloads should call `ControlPacketV5Codec(theirCodec).encode(...)`
    // directly; this default exists for the `MqttCodec.encode → value.serialize(buffer)`
    // path. Messages reach serialize() with their payload already encoded into bytes
    // (eagerEncode in MqttClient.publish<P>), so the cast holds.
    override fun serialize(factory: com.ditchoom.buffer.BufferFactory): ReadBuffer {
        @Suppress("UNCHECKED_CAST")
        return ControlPacketV5OpaqueWireCodec.encode(
            this as ControlPacketV5<com.ditchoom.mqtt.controlpacket.OpaquePublishPayload>,
            com.ditchoom.buffer.codec.EncodeContext.Empty,
            factory,
        )
    }

    override fun serialize(writeBuffer: com.ditchoom.buffer.WriteBuffer) {
        writeBuffer.write(serialize(com.ditchoom.buffer.BufferFactory.Default))
    }

    override fun packetSize(): Int = serialize(com.ditchoom.buffer.BufferFactory.Default).remaining()

    companion object {
        /**
         * Decode a full v5 control-packet wire (`[byte1][VBI(remainingLength)][body]`)
         * with PUBLISH application bytes carried in an [com.ditchoom.mqtt.controlpacket.OpaquePublishPayload]
         * (Pattern #2 — consumer-owned `PlatformBuffer`, byte-exact). For typed payloads,
         * construct `ControlPacketV5Codec(yourPayloadCodec)` directly.
         */
        fun from(buffer: ReadBuffer): ControlPacketV5<com.ditchoom.mqtt.controlpacket.OpaquePublishPayload> =
            try {
                ControlPacketV5OpaqueWireCodec.decode(buffer, DecodeContext.Empty)
            } catch (e: com.ditchoom.buffer.codec.DecodeException) {
                // Replaces the retired `@ProtocolMessage(onUnknownDiscriminator = MalformedPacketException)`
                // mapping. The dispatcher throws DecodeException for unknown packet types and
                // body-overrun, both of which the MQTT 5.0 spec classifies as malformed (§4.13.2).
                throw MalformedPacketException(e.message ?: "malformed control packet")
            }
    }

    @PacketType(value = 1, wire = 0x10)
    @ProtocolMessage
    data class Connect(
        val header: MqttFixedHeader = MqttFixedHeader(0x10u),
        @LengthPrefixed override val protocolName: String,
        val protocolLevel: UByte,
        val connectFlags: ConnectFlagsV5,
        val keepAlive: UShort,
        @LengthPrefixed @UseCodec(MqttRemainingLengthCodec::class) val properties: List<MqttProperty> = emptyList(),
        @LengthPrefixed val clientId: String,
        @When("connectFlags.willFlag")
        @LengthPrefixed
        @UseCodec(MqttRemainingLengthCodec::class) val willProperties: List<MqttProperty>? = null,
        // Wire-shape will fields. Names are distinct from IConnectionRequest's typed
        // accessors (willTopic: TopicName?, willPayload: ReadBuffer?) which are derived
        // via `will: WillConfig`. The codec processor uses these names verbatim for the
        // wire serialization; the typed accessors below convert at the API boundary.
        @When("connectFlags.willFlag") @LengthPrefixed val willTopicString: String? = null,
        // TODO(buffer-v1): Will payload is bytes per §3.1.3.3, not UTF-8. Reverted to String?
        //  pending Will/Password design (multi-param parent threading vs hand-written codec vs
        //  injected-codec-per-field). See memory `mqtt_will_password_deferred.md`.
        @When("connectFlags.willFlag") @LengthPrefixed val willPayloadValue: String? = null,
        @When("connectFlags.usernameFlag") @LengthPrefixed val username: String? = null,
        // TODO(buffer-v1): Password is bytes per §3.1.3.5, not UTF-8. Same deferral as willPayloadValue.
        @When("connectFlags.passwordFlag") @LengthPrefixed override val password: String? = null,
    ) : ControlPacketV5<Nothing>,
        IConnectionRequest {
        init {
            if (header.flags != 0) {
                throw MalformedPacketException(
                    "Reserved fixed-header flags for CONNECT must be 0x0, got 0x${header.flags.toString(16)}",
                )
            }
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
            get() {
                val payloadStr = willPayloadValue
                return if (
                    connectFlags.willFlag &&
                    willTopicString != null &&
                    payloadStr != null
                ) {
                    val payloadBuffer =
                        BufferFactory.Default
                            .allocate(payloadStr.length * 4)
                            .apply {
                                writeString(payloadStr, Charset.UTF8)
                                val written = position()
                                position(0)
                                setLimit(written)
                            }
                    WillConfig.Enabled(
                        TopicName.fromOrThrow(willTopicString),
                        payloadBuffer.slice(),
                        QualityOfService.fromBooleans(connectFlags.willQosBit2, connectFlags.willQosBit1),
                        connectFlags.willRetain,
                    )
                } else {
                    WillConfig.Disabled
                }
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
            ): Connect {
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
                    // TODO(buffer-v1): UTF-8 decode of will payload (Phase A intermediary).
                    willPayloadValue =
                        willEnabled?.payload?.let { buf ->
                            val slice = buf.slice()
                            slice.readString(slice.remaining(), Charset.UTF8)
                        },
                    username = userName,
                    password = password,
                )
            }
        }
    }

    @PacketType(value = 2, wire = 0x20)
    @ProtocolMessage
    data class ConnAck(
        val header: MqttFixedHeader = MqttFixedHeader(0x20u),
        val acknowledgeFlags: UByte,
        val connectReasonCode: UByte,
        @LengthPrefixed @UseCodec(MqttRemainingLengthCodec::class) val properties: List<MqttProperty> = emptyList(),
    ) : ControlPacketV5<Nothing>,
        IConnectionAcknowledgment {
        constructor(
            sessionPresent: Boolean = false,
            connectReason: ReasonCode = SUCCESS,
            properties: ConnAckProperties = ConnAckProperties(),
        ) : this(
            header = MqttFixedHeader(0x20u),
            acknowledgeFlags = (if (sessionPresent) 1u else 0u).toUByte(),
            connectReasonCode = connectReason.byte,
            properties = properties.props,
        )

        init {
            if (header.flags != 0) {
                throw MalformedPacketException(
                    "Reserved fixed-header flags for CONNACK must be 0x0, got 0x${header.flags.toString(16)}",
                )
            }
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
    data class Publish<P : Payload>(
        val header: MqttFixedHeader,
        @LengthPrefixed val topicName: String,
        @When("header.publishHasPacketIdentifier") val packetId: UShort? = null,
        @LengthPrefixed @UseCodec(MqttRemainingLengthCodec::class) val properties: List<MqttProperty> = emptyList(),
        @RemainingBytes val payload: P,
    ) : ControlPacketV5<P>,
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


        /** Typed view of the variable-header properties (§3.3.2.3). */
        val typedProperties: PublishProperties get() = PublishProperties.from(properties)

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
             * Convenience factory — constructs a `Publish<OpaquePublishPayload>` from a
             * raw [ReadBuffer] payload. Pattern #2 (consumer-owned `PlatformBuffer`):
             * allocates a fresh buffer, copies the wire bytes, hands ownership to the
             * handle. Consumers needing a typed payload construct
             * `ControlPacketV5.Publish<MyPayload>(...)` directly.
             */
            fun ofRaw(
                topic: TopicName,
                qos: QualityOfService = QualityOfService.AT_MOST_ONCE,
                payload: ReadBuffer? = null,
                dup: Boolean = false,
                retain: Boolean = false,
                packetIdentifier: Int = NO_PACKET_ID,
                properties: PublishProperties = PublishProperties(),
            ): Publish<com.ditchoom.mqtt.controlpacket.OpaquePublishPayload> {
                val header = MqttFixedHeader(makePublishHeaderByte(dup, qos, retain))
                val pid =
                    if (qos == QualityOfService.AT_MOST_ONCE || packetIdentifier == NO_PACKET_ID) {
                        null
                    } else {
                        packetIdentifier.toUShort()
                    }
                val factory = com.ditchoom.buffer.BufferFactory.Default
                val remaining = payload?.remaining() ?: 0
                val dst = factory.allocate(remaining)
                if (remaining > 0 && payload != null) dst.write(payload)
                dst.resetForRead()
                val opaque =
                    com.ditchoom.mqtt.controlpacket.OpaquePublishPayload(
                        com.ditchoom.buffer.codec.opaqueBytesFrom(dst),
                    )
                return Publish(
                    header = header,
                    topicName = topic.toString(),
                    packetId = pid,
                    properties = properties.props,
                    payload = opaque,
                )
            }

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
        val header: MqttFixedHeader = MqttFixedHeader(0x40u),
        val packetId: UShort,
        @When("remaining >= 1") val reasonCode: UByte? = null,
        @When("remaining >= 1")
        @LengthPrefixed
        @UseCodec(MqttRemainingLengthCodec::class) val properties: List<MqttProperty>? = null,
    ) : ControlPacketV5<Nothing>,
        IPublishAcknowledgment {
        constructor(
            packetIdentifier: Int,
            reasonCode: ReasonCode = SUCCESS,
            reasonString: String? = null,
            userProperty: List<Pair<String, String>> = emptyList(),
        ) : this(
            header = MqttFixedHeader(0x40u),
            packetId = packetIdentifier.toUShort(),
            reasonCode = collapseReasonCode(reasonCode, reasonString, userProperty),
            properties = ackProps(reasonString, userProperty, forceEmpty = reasonCode != SUCCESS),
        )

        init {
            if (header.flags != 0) {
                throw MalformedPacketException(
                    "Reserved fixed-header flags for PUBACK must be 0x0, got 0x${header.flags.toString(16)}",
                )
            }
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
    }

    @PacketType(value = 5, wire = 0x50)
    @ProtocolMessage
    data class PubRec(
        val header: MqttFixedHeader = MqttFixedHeader(0x50u),
        val packetId: UShort,
        @When("remaining >= 1") val reasonCode: UByte? = null,
        @When("remaining >= 1")
        @LengthPrefixed
        @UseCodec(MqttRemainingLengthCodec::class) val properties: List<MqttProperty>? = null,
    ) : ControlPacketV5<Nothing>,
        IPublishReceived {
        constructor(
            packetIdentifier: Int,
            reasonCode: ReasonCode = SUCCESS,
            reasonString: String? = null,
            userProperty: List<Pair<String, String>> = emptyList(),
        ) : this(
            header = MqttFixedHeader(0x50u),
            packetId = packetIdentifier.toUShort(),
            reasonCode = collapseReasonCode(reasonCode, reasonString, userProperty),
            properties = ackProps(reasonString, userProperty, forceEmpty = reasonCode != SUCCESS),
        )

        init {
            if (header.flags != 0) {
                throw MalformedPacketException(
                    "Reserved fixed-header flags for PUBREC must be 0x0, got 0x${header.flags.toString(16)}",
                )
            }
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

        override fun expectedResponse(
            reasonCode: ReasonCode,
            reasonString: String?,
            userProperty: List<Pair<String, String>>,
        ): IPublishRelease = PubRel(packetIdentifier, reasonCode, reasonString, userProperty)
    }

    @PacketType(value = 6, wire = 0x62)
    @ProtocolMessage
    data class PubRel(
        val header: MqttFixedHeader = MqttFixedHeader(0x62u),
        val packetId: UShort,
        @When("remaining >= 1") val reasonCode: UByte? = null,
        @When("remaining >= 1")
        @LengthPrefixed
        @UseCodec(MqttRemainingLengthCodec::class) val properties: List<MqttProperty>? = null,
    ) : ControlPacketV5<Nothing>,
        IPublishRelease {
        constructor(
            packetIdentifier: Int,
            reasonCode: ReasonCode = SUCCESS,
            reasonString: String? = null,
            userProperty: List<Pair<String, String>> = emptyList(),
        ) : this(
            header = MqttFixedHeader(0x62u),
            packetId = packetIdentifier.toUShort(),
            reasonCode = collapseReasonCode(reasonCode, reasonString, userProperty),
            properties = ackProps(reasonString, userProperty, forceEmpty = reasonCode != SUCCESS),
        )

        init {
            // §3.6.1: reserved low-nibble bits MUST be 0010.
            if (header.flags != 0b10) {
                throw MalformedPacketException(
                    "Reserved fixed-header flags for PUBREL must be 0x2, got 0x${header.flags.toString(16)}",
                )
            }
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

        override fun expectedResponse(
            reasonCode: ReasonCode,
            reasonString: String?,
            userProperty: List<Pair<String, String>>,
        ): IPublishComplete = PubComp(packetIdentifier, reasonCode, reasonString, userProperty)
    }

    @PacketType(value = 7, wire = 0x70)
    @ProtocolMessage
    data class PubComp(
        val header: MqttFixedHeader = MqttFixedHeader(0x70u),
        val packetId: UShort,
        @When("remaining >= 1") val reasonCode: UByte? = null,
        @When("remaining >= 1")
        @LengthPrefixed
        @UseCodec(MqttRemainingLengthCodec::class) val properties: List<MqttProperty>? = null,
    ) : ControlPacketV5<Nothing>,
        IPublishComplete {
        constructor(packetIdentifier: UShort, reasonCode: ReasonCode = SUCCESS) :
            this(
                header = MqttFixedHeader(0x70u),
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
            header = MqttFixedHeader(0x70u),
            packetId = packetIdentifier.toUShort(),
            reasonCode = collapseReasonCode(reasonCode, reasonString, userProperty),
            properties = ackProps(reasonString, userProperty, forceEmpty = reasonCode != SUCCESS),
        )

        init {
            if (header.flags != 0) {
                throw MalformedPacketException(
                    "Reserved fixed-header flags for PUBCOMP must be 0x0, got 0x${header.flags.toString(16)}",
                )
            }
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
    }

    @PacketType(value = 12, wire = 0xC0)
    @ProtocolMessage
    data class PingReq(
        val header: MqttFixedHeader = MqttFixedHeader(0xC0u),
    ) : ControlPacketV5<Nothing>,
        IPingRequest {
        init {
            if (header.flags != 0) {
                throw MalformedPacketException(
                    "Reserved fixed-header flags for PINGREQ must be 0x0, got 0x${header.flags.toString(16)}",
                )
            }
        }

        override val controlPacketValue: Byte get() = 12
        override val direction: DirectionOfFlow get() = DirectionOfFlow.CLIENT_TO_SERVER
    }

    @PacketType(value = 13, wire = 0xD0)
    @ProtocolMessage
    data class PingResp(
        val header: MqttFixedHeader = MqttFixedHeader(0xD0u),
    ) : ControlPacketV5<Nothing>,
        IPingResponse {
        init {
            if (header.flags != 0) {
                throw MalformedPacketException(
                    "Reserved fixed-header flags for PINGRESP must be 0x0, got 0x${header.flags.toString(16)}",
                )
            }
        }

        override val controlPacketValue: Byte get() = 13
        override val direction: DirectionOfFlow get() = DirectionOfFlow.SERVER_TO_CLIENT
    }

    @PacketType(value = 14, wire = 0xE0)
    @ProtocolMessage
    data class Disconnect(
        val header: MqttFixedHeader = MqttFixedHeader(0xE0u),
        @When("remaining >= 1") val reasonCode: UByte? = null,
        @When("remaining >= 1")
        @LengthPrefixed
        @UseCodec(MqttRemainingLengthCodec::class) val properties: List<MqttProperty>? = null,
    ) : ControlPacketV5<Nothing>,
        IDisconnectNotification {
        // No default on `reasonCode` so `Disconnect()` resolves unambiguously to the
        // primary wire-shape constructor; the typed builder is reached via named args.
        constructor(
            reasonCode: ReasonCode,
            sessionExpiryIntervalSeconds: ULong? = null,
            reasonString: String? = null,
            userProperty: List<Pair<String, String>> = emptyList(),
            serverReference: String? = null,
        ) : this(
            header = MqttFixedHeader(0xE0u),
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
            if (header.flags != 0) {
                throw MalformedPacketException(
                    "Reserved fixed-header flags for DISCONNECT must be 0x0, got 0x${header.flags.toString(16)}",
                )
            }
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
    }

    @PacketType(value = 15, wire = 0xF0)
    @ProtocolMessage
    data class Auth(
        val header: MqttFixedHeader = MqttFixedHeader(0xF0u),
        @When("remaining >= 1") val reasonCode: UByte? = null,
        @When("remaining >= 1")
        @LengthPrefixed
        @UseCodec(MqttRemainingLengthCodec::class) val properties: List<MqttProperty>? = null,
    ) : ControlPacketV5<Nothing> {
        // No default on `reasonCode` so `Auth()` resolves unambiguously to the primary
        // wire-shape constructor; the typed builder is reached via named args.
        constructor(
            reasonCode: ReasonCode,
            reasonString: String? = null,
            userProperty: List<Pair<String, String>> = emptyList(),
        ) : this(
            header = MqttFixedHeader(0xF0u),
            reasonCode =
                if (reasonCode == SUCCESS && reasonString == null && userProperty.isEmpty()) {
                    null
                } else {
                    reasonCode.byte
                },
            properties = ackProps(reasonString, userProperty, forceEmpty = reasonCode != SUCCESS),
        )

        init {
            if (header.flags != 0) {
                throw MalformedPacketException(
                    "Reserved fixed-header flags for AUTH must be 0x0, got 0x${header.flags.toString(16)}",
                )
            }
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
    }

    @PacketType(value = 8, wire = 0x82)
    @ProtocolMessage
    data class Subscribe(
        val header: MqttFixedHeader = MqttFixedHeader(0x82u),
        val packetId: UShort,
        @LengthPrefixed @UseCodec(MqttRemainingLengthCodec::class) val properties: List<MqttProperty> = emptyList(),
        @RemainingBytes val subscriptionEntries: List<SubscriptionV5Entry>,
    ) : ControlPacketV5<Nothing>,
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
            header = MqttFixedHeader(0x82u),
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
            // §3.8.1: reserved low-nibble bits MUST be 0010.
            if (header.flags != 0b10) {
                throw MalformedPacketException(
                    "Reserved fixed-header flags for SUBSCRIBE must be 0x2, got 0x${header.flags.toString(16)}",
                )
            }
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
    }

    @PacketType(value = 9, wire = 0x90)
    @ProtocolMessage
    data class SubAck(
        val header: MqttFixedHeader = MqttFixedHeader(0x90u),
        val packetId: UShort,
        @LengthPrefixed @UseCodec(MqttRemainingLengthCodec::class) val properties: List<MqttProperty> = emptyList(),
        @RemainingBytes val reasonCodeEntries: List<SubAckReasonCodeV5>,
    ) : ControlPacketV5<Nothing>,
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
            header = MqttFixedHeader(0x90u),
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
            if (header.flags != 0) {
                throw MalformedPacketException(
                    "Reserved fixed-header flags for SUBACK must be 0x0, got 0x${header.flags.toString(16)}",
                )
            }
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
    }

    @PacketType(value = 10, wire = 0xA2)
    @ProtocolMessage
    data class Unsubscribe(
        val header: MqttFixedHeader = MqttFixedHeader(0xA2u),
        val packetId: UShort,
        @LengthPrefixed @UseCodec(MqttRemainingLengthCodec::class) val properties: List<MqttProperty> = emptyList(),
        @RemainingBytes val topicEntries: List<TopicFilterV5Entry>,
    ) : ControlPacketV5<Nothing>,
        IUnsubscribeRequest {
        constructor(
            packetIdentifier: UShort,
            topics: Set<TopicFilter>,
            userProperty: List<Pair<String, String>> = emptyList(),
        ) : this(
            header = MqttFixedHeader(0xA2u),
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
            // §3.10.1: reserved low-nibble bits MUST be 0010.
            if (header.flags != 0b10) {
                throw MalformedPacketException(
                    "Reserved fixed-header flags for UNSUBSCRIBE must be 0x2, got 0x${header.flags.toString(16)}",
                )
            }
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
    }

    @PacketType(value = 11, wire = 0xB0)
    @ProtocolMessage
    data class UnsubAck(
        val header: MqttFixedHeader = MqttFixedHeader(0xB0u),
        val packetId: UShort,
        @LengthPrefixed @UseCodec(MqttRemainingLengthCodec::class) val properties: List<MqttProperty> = emptyList(),
        @RemainingBytes val reasonCodeEntries: List<UnsubAckReasonCodeV5>,
    ) : ControlPacketV5<Nothing>,
        IUnsubscribeAcknowledgment {
        constructor(
            packetIdentifier: Int,
            reasonString: String? = null,
            userProperty: List<Pair<String, String>> = emptyList(),
            reasonCodes: List<ReasonCode> = listOf(SUCCESS),
        ) : this(
            header = MqttFixedHeader(0xB0u),
            packetId = packetIdentifier.toUShort(),
            properties = ackProps(reasonString, userProperty) ?: emptyList(),
            reasonCodeEntries = reasonCodes.map { UnsubAckReasonCodeV5(it.byte) },
        )

        init {
            if (header.flags != 0) {
                throw MalformedPacketException(
                    "Reserved fixed-header flags for UNSUBACK must be 0x0, got 0x${header.flags.toString(16)}",
                )
            }
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
typealias ConnectionRequest = ControlPacketV5.Connect

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
        if (reasonString != null) add(ReasonString(value = reasonString))
        for ((k, v) in userProperty) add(UserProperty(key = k, value = v))
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
            add(SessionExpiryInterval(seconds = sessionExpiryIntervalSeconds.toUInt()))
        }
        if (reasonString != null) add(ReasonString(value = reasonString))
        for ((k, v) in userProperty) add(UserProperty(key = k, value = v))
        if (serverReference != null) add(ServerReference(value = serverReference))
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

// publishPropertyEncodeContext / publishPropertyDecodeContext removed by Phase B-3a:
// AuthenticationData / CorrelationData property variants are intermediated via
// `@LengthPrefixed val value: String` (UTF-8) until the Phase B typed-payload design
// pick lands. The generated MqttPropertyCodec is non-generic and needs no context keys.

// Cached codec instance for the hot wire-write path. Lives at top-level (not on
// the sealed parent's companion) so KSP's `@DispatchOn` discovery isn't confused
// by a companion-init reference to its own generated class.
internal val ControlPacketV5OpaqueWireCodec: ControlPacketV5Codec<com.ditchoom.mqtt.controlpacket.OpaquePublishPayload> by lazy {
    ControlPacketV5Codec(com.ditchoom.mqtt.controlpacket.OpaquePublishPayloadCodec)
}
