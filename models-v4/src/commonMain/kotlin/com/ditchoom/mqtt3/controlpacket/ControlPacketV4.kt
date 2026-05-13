package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.codec.Payload
import com.ditchoom.buffer.codec.asReadBuffer
import com.ditchoom.buffer.codec.annotations.DispatchOn
import com.ditchoom.buffer.codec.annotations.FramedBy
import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes
import com.ditchoom.buffer.codec.annotations.When
import com.ditchoom.mqtt.MalformedPacketException
import com.ditchoom.mqtt.MqttWarning
import com.ditchoom.mqtt.ProtocolError
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.ControlPacketFactory
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
import com.ditchoom.mqtt.controlpacket.IUnsubscribeAcknowledgment
import com.ditchoom.mqtt.controlpacket.IUnsubscribeRequest
import com.ditchoom.mqtt.controlpacket.MqttFixedHeader
import com.ditchoom.mqtt.controlpacket.MqttRemainingLengthCodec
import com.ditchoom.mqtt.controlpacket.NO_PACKET_ID
import com.ditchoom.mqtt.controlpacket.OpaquePublishPayload
import com.ditchoom.mqtt.controlpacket.OpaquePublishPayloadCodec
import com.ditchoom.mqtt.controlpacket.PublishMessage
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.QualityOfService.AT_LEAST_ONCE
import com.ditchoom.mqtt.controlpacket.QualityOfService.AT_MOST_ONCE
import com.ditchoom.mqtt.controlpacket.QualityOfService.EXACTLY_ONCE
import com.ditchoom.mqtt.controlpacket.TopicFilter
import com.ditchoom.mqtt.controlpacket.TopicName
import com.ditchoom.mqtt.controlpacket.WillConfig
import com.ditchoom.mqtt.controlpacket.format.ReasonCode
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.GRANTED_QOS_0
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.GRANTED_QOS_1
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.GRANTED_QOS_2
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.UNSPECIFIED_ERROR
import com.ditchoom.mqtt.controlpacket.format.fixed.DirectionOfFlow
import com.ditchoom.mqtt.controlpacket.validControlPacketIdentifierRange
import kotlin.jvm.JvmInline

// ── Wire-shape element types for list-payload packets ─────────────────────

/**
 * Wire model for a single SUBSCRIBE entry: topic filter + requested QoS byte.
 */
@ProtocolMessage
data class SubscriptionEntry(
    @LengthPrefixed val filter: String,
    val qos: UByte,
) : ISubscription {
    override val topicFilter: TopicFilter get() = TopicFilter.fromOrThrow(filter)
    override val maximumQos: QualityOfService
        get() =
            QualityOfService.fromBooleans(
                qos.toInt().shr(1) and 1 == 1,
                qos.toInt() and 1 == 1,
            )
}

/**
 * Discriminator byte for [SubAckReturnCode] sealed-tree dispatch.
 * SUBACK §3.9.3 enumerates exactly four legal values (0x00 / 0x01 / 0x02 / 0x80);
 * the dispatcher routes the wire byte to one of [SubAckReturnCode]'s `data object`
 * variants, eliminating per-element heap allocation when reassembling the list.
 */
@JvmInline
@ProtocolMessage
value class SubAckReturnCodeRaw(
    val raw: UByte,
) {
    @com.ditchoom.buffer.codec.annotations.DispatchValue
    val id: Int get() = raw.toInt()
}

/**
 * Typed return code for MQTT v3.1.1 SUBACK (§3.9.3). Sealed-tree dispatch over
 * [SubAckReturnCodeRaw] folds the byte-value-space into the type system; the
 * codec layer reuses the same four singleton instances regardless of list length.
 */
@DispatchOn(SubAckReturnCodeRaw::class)
@ProtocolMessage
sealed interface SubAckReturnCode {
    /** §3.9.3 — `0x00` Success - Maximum QoS 0. */
    @PacketType(value = 0x00)
    @ProtocolMessage
    data object SuccessMaximumQoS0 : SubAckReturnCode

    /** §3.9.3 — `0x01` Success - Maximum QoS 1. */
    @PacketType(value = 0x01)
    @ProtocolMessage
    data object SuccessMaximumQoS1 : SubAckReturnCode

    /** §3.9.3 — `0x02` Success - Maximum QoS 2. */
    @PacketType(value = 0x02)
    @ProtocolMessage
    data object SuccessMaximumQoS2 : SubAckReturnCode

    /** §3.9.3 — `0x80` Failure. */
    @PacketType(value = 0x80)
    @ProtocolMessage
    data object Failure : SubAckReturnCode

    companion object {
        /** Map a domain-level [com.ditchoom.mqtt.controlpacket.format.ReasonCode] byte to its sealed [SubAckReturnCode] variant. */
        fun fromByte(byte: UByte): SubAckReturnCode =
            when (byte) {
                0x00.toUByte() -> SuccessMaximumQoS0
                0x01.toUByte() -> SuccessMaximumQoS1
                0x02.toUByte() -> SuccessMaximumQoS2
                0x80.toUByte() -> Failure
                else -> throw MalformedPacketException("Invalid SUBACK return code 0x${byte.toString(16)}")
            }
    }
}

/**
 * Wire model for a single UNSUBSCRIBE topic filter entry (length-prefixed string).
 */
@ProtocolMessage
data class TopicFilterEntry(
    @LengthPrefixed val filter: String,
)

// ── CONNECT flags byte (§3.1.2.3) ─────────────────────────────────────────

@JvmInline
value class ConnectV4Flags(
    val raw: UByte,
) {
    val reserved: Boolean get() = raw.toInt() and 1 == 1
    val cleanSession: Boolean get() = (raw.toInt() shr 1) and 1 == 1
    val willFlag: Boolean get() = (raw.toInt() shr 2) and 1 == 1
    val willQos: Int get() = (raw.toInt() shr 3) and 3
    val willRetain: Boolean get() = (raw.toInt() shr 5) and 1 == 1
    val passwordFlag: Boolean get() = (raw.toInt() shr 6) and 1 == 1
    val usernameFlag: Boolean get() = (raw.toInt() shr 7) and 1 == 1

    companion object {
        fun from(
            cleanSession: Boolean = false,
            willFlag: Boolean = false,
            willQos: QualityOfService = AT_MOST_ONCE,
            willRetain: Boolean = false,
            hasPassword: Boolean = false,
            hasUserName: Boolean = false,
        ): ConnectV4Flags {
            val raw =
                (if (hasUserName) 0b10000000 else 0) or
                    (if (hasPassword) 0b1000000 else 0) or
                    (if (willRetain) 0b100000 else 0) or
                    (willQos.integerValue.toInt() shl 3) or
                    (if (willFlag) 0b100 else 0) or
                    (if (cleanSession) 0b10 else 0)
            return ConnectV4Flags(raw.toUByte())
        }
    }
}

// ── Sealed root ───────────────────────────────────────────────────────────

/**
 * The MQTT specification defines fifteen different types of MQTT Control Packet, for example the
 * PublishMessage packet is used to convey Application Messages.
 *
 * Variants are declared as top-level classes (not nested) so legacy tests retain access to
 * `ConnectionRequest.VariableHeader` and `ConnectionRequest.Payload` through the variant's
 * own type. (Kotlin typealiases of generic types do not expose nested types.)
 *
 * @see https://docs.oasis-open.org/mqtt/mqtt/v3.1.1/os/mqtt-v3.1.1-os.html
 */
@DispatchOn(MqttFixedHeader::class)
@FramedBy(MqttRemainingLengthCodec::class, after = "header")
@ProtocolMessage
sealed interface ControlPacketV4<out P : Payload> : ControlPacket {
    override val mqttVersion: Byte get() = 4
    override val controlPacketFactory: ControlPacketFactory get() = ControlPacketV4Factory

    // Override the legacy `ControlPacket.serialize(...)` / `packetSize()` (which use the
    // gone encodeBody/remainingLength path) to route through the v4 sealed-tree codec
    // with [OpaquePublishPayload] as the PUBLISH payload carrier. Production callers with
    // typed PUBLISH payloads should call `ControlPacketV4Codec(theirCodec).encode(...)`
    // directly; this default exists for the `MqttCodec.encode → value.serialize(buffer)`
    // path that consumes ControlPackets at the wire-write boundary. Messages reach
    // serialize() with their payload already encoded into bytes (eagerEncode in
    // MqttClient.publish<P>), so the cast to ControlPacketV4<OpaquePublishPayload> holds.
    override fun serialize(factory: com.ditchoom.buffer.BufferFactory): ReadBuffer {
        @Suppress("UNCHECKED_CAST")
        return ControlPacketV4Codec(OpaquePublishPayloadCodec).encode(
            this as ControlPacketV4<OpaquePublishPayload>,
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
         * Decode a full v4 control-packet wire (`[byte1][VBI(remainingLength)][body]`) with
         * PUBLISH application bytes carried in an [OpaquePublishPayload] (Pattern #2 —
         * consumer-owned `PlatformBuffer`, byte-exact). For typed payloads, construct
         * `ControlPacketV4Codec(yourPayloadCodec)` directly.
         */
        fun from(buffer: ReadBuffer): ControlPacketV4<OpaquePublishPayload> =
            try {
                ControlPacketV4Codec(OpaquePublishPayloadCodec)
                    .decode(buffer, com.ditchoom.buffer.codec.DecodeContext.Empty)
            } catch (e: com.ditchoom.buffer.codec.DecodeException) {
                throw MalformedPacketException(e.message ?: "malformed control packet")
            }
    }
}

// ── Reserved (wire 0x00) ───────────────────────────────────────────────────

@PacketType(value = 0)
@ProtocolMessage
data class Reserved(
    val header: MqttFixedHeader = MqttFixedHeader(0x00u),
) : ControlPacketV4<Nothing> {
    override val controlPacketValue: Byte get() = 0
    override val direction: DirectionOfFlow get() = DirectionOfFlow.BIDIRECTIONAL
}

// ── CONNECT (§3.1) ────────────────────────────────────────────────────────

/**
 * 3.1 CONNECT — Client requests a connection to a Server.
 *
 * Wire-shape data class. Existing `(VariableHeader, Payload)` and high-level convenience
 * constructors are preserved as secondary constructors that delegate to the wire primary;
 * the legacy `variableHeader` and `payload` typed views are derived getters.
 */
@PacketType(value = 1, wire = 0x10)
@ProtocolMessage
data class ConnectionRequest(
    val header: MqttFixedHeader = MqttFixedHeader(0x10u),
    @LengthPrefixed override val protocolName: String = "MQTT",
    val protocolLevel: UByte = 4u,
    val connectFlags: ConnectV4Flags = ConnectV4Flags(0u),
    val keepAlive: UShort = UShort.MAX_VALUE,
    @LengthPrefixed val clientId: String = "",
    @When("connectFlags.willFlag") @LengthPrefixed val willTopicString: String? = null,
    // TODO(buffer-v1): Will payload is bytes per §3.1.3.3, not UTF-8. Reverted to String?
    //  pending Will/Password design (multi-param parent threading vs hand-written codec vs
    //  injected-codec-per-field). See memory `mqtt_will_password_deferred.md`.
    @When("connectFlags.willFlag") @LengthPrefixed val willPayloadValue: String? = null,
    @When("connectFlags.usernameFlag") @LengthPrefixed val username: String? = null,
    // TODO(buffer-v1): Password is bytes per §3.1.3.5, not UTF-8. Same deferral as willPayloadValue.
    @When("connectFlags.passwordFlag") @LengthPrefixed val passwordValue: String? = null,
) : ControlPacketV4<Nothing>,
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
        if (connectFlags.willQos == 3) {
            throw MalformedPacketException("Will QoS = 3 is a Malformed Packet (§3.1.2-14)")
        }
    }

    override val controlPacketValue: Byte get() = 1
    override val direction: DirectionOfFlow get() = DirectionOfFlow.CLIENT_TO_SERVER

    // ── IConnectionRequest typed accessors ──
    override val clientIdentifier: String get() = clientId
    override val keepAliveTimeoutSeconds: UShort get() = keepAlive
    override val protocolVersion: Int get() = protocolLevel.toInt()
    override val cleanStart: Boolean get() = connectFlags.cleanSession
    override val hasUserName: Boolean get() = connectFlags.usernameFlag
    override val hasPassword: Boolean get() = connectFlags.passwordFlag
    override val userName: String? get() = username

    override val password: String? get() = passwordValue

    override val will: WillConfig
        get() {
            val payloadStr = willPayloadValue
            return if (
                connectFlags.willFlag &&
                willTopicString != null &&
                payloadStr != null
            ) {
                val payloadBuffer =
                    BufferFactory.Default.allocate(payloadStr.length * 4).apply {
                        writeString(payloadStr, com.ditchoom.buffer.Charset.UTF8)
                        val written = position()
                        position(0)
                        setLimit(written)
                    }
                WillConfig.Enabled(
                    TopicName.fromOrThrow(willTopicString),
                    payloadBuffer.slice(),
                    QualityOfService.fromBooleans(
                        (connectFlags.willQos shr 1) and 1 == 1,
                        connectFlags.willQos and 1 == 1,
                    ),
                    connectFlags.willRetain,
                )
            } else {
                WillConfig.Disabled
            }
        }

    // ── Legacy `variableHeader` / `payload` typed views (preserve API) ──

    val variableHeader: VariableHeader
        get() =
            VariableHeader(
                protocolName = protocolName,
                protocolLevel = protocolLevel,
                hasUserName = connectFlags.usernameFlag,
                hasPassword = connectFlags.passwordFlag,
                willRetain = connectFlags.willRetain,
                willQos =
                    QualityOfService.fromBooleans(
                        (connectFlags.willQos shr 1) and 1 == 1,
                        connectFlags.willQos and 1 == 1,
                    ),
                willFlag = connectFlags.willFlag,
                cleanSession = connectFlags.cleanSession,
                keepAliveSeconds = keepAlive.toInt(),
            )

    val payload: Payload
        get() =
            Payload(
                clientId = clientId,
                willTopic = willTopicString?.let { TopicName.fromOrThrow(it) },
                // Will payload reflects the (currently String?-typed) wire field. Materialize a
                // ReadBuffer for the legacy accessor. See willPayloadValue TODO above.
                willPayload =
                    willPayloadValue?.let { s ->
                        BufferFactory.Default
                            .allocate(s.length * 4)
                            .apply {
                                writeString(s, com.ditchoom.buffer.Charset.UTF8)
                                val written = position()
                                position(0)
                                setLimit(written)
                            }.slice()
                    },
                userName = username,
                password = password,
            )

    override fun validate(): MqttWarning? {
        if (connectFlags.willFlag &&
            (willPayloadValue == null || willTopicString == null)
        ) {
            return MqttWarning(
                "[MQTT-3.1.2-9]",
                "If the Will Flag is set to " +
                    "1, the Will QoS and Will Retain fields in the Connect Flags will be used by the Server, " +
                    "and the Will Properties, Will Topic and Will Message fields MUST be present in the Payload.",
            )
        }
        if (connectFlags.usernameFlag && username == null) {
            return MqttWarning(
                "[MQTT-3.1.2-17]",
                "If the User Name Flag is set" +
                    " to 1, a User Name MUST be present in the Payload",
            )
        }
        if (!connectFlags.usernameFlag && username != null) {
            return MqttWarning(
                "[MQTT-3.1.2-16]",
                "If the User Name Flag is set " +
                    "to 0, a User Name MUST NOT be present in the Payload",
            )
        }
        if (connectFlags.passwordFlag && password == null) {
            return MqttWarning(
                "[MQTT-3.1.2-19]",
                "If the Password Flag is set" +
                    " to 1, a Password MUST be present in the Payload",
            )
        }
        if (!connectFlags.passwordFlag && password != null) {
            return MqttWarning(
                "[MQTT-3.1.2-18]",
                "If the Password Flag is set " +
                    "to 0, a Password MUST NOT be present in the Payload",
            )
        }
        return variableHeader.validateOrGetWarning()
    }

    // ── Legacy convenience constructors ──

    constructor(
        variableHeader: VariableHeader,
        payload: Payload = Payload(),
    ) : this(
        header = MqttFixedHeader(0x10u),
        protocolName = variableHeader.protocolName,
        protocolLevel = variableHeader.protocolLevel,
        connectFlags =
            ConnectV4Flags.from(
                cleanSession = variableHeader.cleanSession,
                willFlag = variableHeader.willFlag,
                willQos = variableHeader.willQos,
                willRetain = variableHeader.willRetain,
                hasPassword = variableHeader.hasPassword,
                hasUserName = variableHeader.hasUserName,
            ),
        keepAlive = variableHeader.keepAliveSeconds.toUShort(),
        clientId = payload.clientId,
        willTopicString = payload.willTopic?.toString(),
        willPayloadValue =
            payload.willPayload?.let { buf ->
                val slice = buf.slice()
                slice.readString(slice.remaining(), com.ditchoom.buffer.Charset.UTF8)
            },
        username = payload.userName,
        passwordValue = payload.password,
    )

    constructor(
        clientId: String,
        keepAliveSeconds: Int = 3600,
        cleanSession: Boolean = false,
        userName: String? = null,
        password: String? = null,
        will: WillConfig = WillConfig.Disabled,
        protocolName: String = "MQTT",
        protocolLevel: UByte = 4u,
    ) : this(
        VariableHeader(
            protocolName = protocolName,
            protocolLevel = protocolLevel,
            cleanSession = cleanSession,
            keepAliveSeconds = keepAliveSeconds,
            hasUserName = userName != null,
            hasPassword = password != null,
            willRetain = (will as? WillConfig.Enabled)?.retain ?: false,
            willFlag = will is WillConfig.Enabled,
            willQos = (will as? WillConfig.Enabled)?.qos ?: AT_MOST_ONCE,
        ),
        Payload(
            clientId,
            (will as? WillConfig.Enabled)?.topic,
            (will as? WillConfig.Enabled)?.payload,
            userName,
            password,
        ),
    )

    // ── Legacy nested types preserved for API compatibility ──

    data class VariableHeader(
        val protocolName: String = "MQTT",
        val protocolLevel: UByte = 4.toUByte(),
        val hasUserName: Boolean = false,
        val hasPassword: Boolean = false,
        val willRetain: Boolean = false,
        val willQos: QualityOfService = AT_MOST_ONCE,
        val willFlag: Boolean = false,
        val cleanSession: Boolean = false,
        val keepAliveSeconds: Int = UShort.MAX_VALUE.toInt(),
    ) {
        fun validateOrGetWarning(): MqttWarning? {
            if (!willFlag && willRetain) {
                return MqttWarning(
                    "[MQTT-3.1.2-13]",
                    "If the Will Flag is set to 0, then Will Retain MUST be set to 0",
                )
            }
            if (!willFlag && willQos != AT_MOST_ONCE) {
                return MqttWarning(
                    "[MQTT-3.1.2-11]",
                    "If the Will Flag is set to 0, then Will QoS MUST be set to 0 (AT_MOST_ONCE)",
                )
            }
            return null
        }
    }

    data class Payload(
        val clientId: String = "",
        val willTopic: TopicName? = null,
        val willPayload: ReadBuffer? = null,
        val userName: String? = null,
        val password: String? = null,
    )
}

// ── CONNACK (§3.2) ────────────────────────────────────────────────────────

/**
 * The CONNACK packet is the packet sent by the Server in response to a CONNECT packet.
 *
 * Wire-shape data class. The wire `MqttFixedHeader` is named `header` so the legacy
 * `header: VariableHeader` accessor (used by tests) is preserved.
 */
@PacketType(value = 2, wire = 0x20)
@ProtocolMessage
data class ConnectionAcknowledgment(
    val header: MqttFixedHeader = MqttFixedHeader(0x20u),
    val acknowledgeFlags: UByte = 0u,
    val returnCode: UByte = 0u,
) : ControlPacketV4<Nothing>,
    IConnectionAcknowledgment {
    init {
        if (header.flags != 0) {
            throw MalformedPacketException(
                "Reserved fixed-header flags for CONNACK must be 0x0, got 0x${header.flags.toString(16)}",
            )
        }
        if ((acknowledgeFlags.toInt() and 0xFE) != 0) {
            throw MalformedPacketException(
                "CONNACK Acknowledge Flags reserved bits 1-7 must be 0 (§3.2.2.1), " +
                    "got 0x${acknowledgeFlags.toString(16)}",
            )
        }
    }

    override val controlPacketValue: Byte get() = 2
    override val direction: DirectionOfFlow get() = DirectionOfFlow.SERVER_TO_CLIENT

    override val sessionPresent: Boolean get() = (acknowledgeFlags.toInt() and 0x01) == 1
    override val isSuccessful: Boolean get() = variableHeader.connectReason == VariableHeader.ReturnCode.CONNECTION_ACCEPTED
    override val connectionReason: String get() = variableHeader.connectReason.name

    /** Legacy [VariableHeader]-typed view preserving the pre-migration API. */
    val variableHeader: VariableHeader
        get() {
            val normalized =
                if (returnCode > 5.toUByte()) VariableHeader.ReturnCode.RESERVED.value else returnCode
            val rc =
                connackReturnCode[normalized]
                    ?: throw MalformedPacketException(
                        "Invalid property type found in MQTT payload $normalized",
                    )
            return VariableHeader(sessionPresent, rc)
        }

    constructor(variableHeader: VariableHeader = VariableHeader()) : this(
        header = MqttFixedHeader(0x20u),
        acknowledgeFlags = (if (variableHeader.sessionPresent) 1u else 0u).toUByte(),
        returnCode = variableHeader.connectReason.value,
    )

    constructor(sessionPresent: Boolean, connectReason: VariableHeader.ReturnCode) :
        this(VariableHeader(sessionPresent, connectReason))

    data class VariableHeader(
        val sessionPresent: Boolean = false,
        val connectReason: ReturnCode = ReturnCode.CONNECTION_ACCEPTED,
    ) {
        enum class ReturnCode(
            val value: UByte,
        ) {
            CONNECTION_ACCEPTED(0.toUByte()),
            CONNECTION_REFUSED_UNACCEPTABLE_PROTOCOL_VERSION(1.toUByte()),
            CONNECTION_REFUSED_IDENTIFIER_REJECTED(2.toUByte()),
            CONNECTION_REFUSED_SERVER_UNAVAILABLE(3.toUByte()),
            CONNECTION_REFUSED_BAD_USER_NAME_OR_PASSWORD(4.toUByte()),
            CONNECTION_REFUSED_NOT_AUTHORIZED(5.toUByte()),
            RESERVED(6.toUByte()),
        }
    }
}

typealias CONNACK = ConnectionAcknowledgment

// Factory functions matching legacy `ConnectionRequest(...)` call shapes were retained
// while the class was generic over `<@Payload WP>`. After the directional-codec migration
// the will payload routes through `@UseCodec(BufferPayloadCodec)` and the class is no
// longer generic, so these factories are unnecessary — call the class directly.

// ── PUBLISH (§3.3) ────────────────────────────────────────────────────────

/**
 * MQTT 3.1.1 PUBLISH (§3.3). Wire-shape data class; typed accessors on the [PublishMessage]
 * interface delegate to fields decoded from the fixed-header byte.
 *
 * The `<@Payload P>` type parameter enables zero-copy slice forwarding on decode and
 * caller-supplied encoding on encode. Production callers use `PublishMessageV4<ReadBuffer>`.
 */
@PacketType(value = 3)
@ProtocolMessage
data class PublishMessageV4<P : Payload>(
    val header: MqttFixedHeader,
    @LengthPrefixed val topicName: String,
    @When("header.publishHasPacketIdentifier") val packetId: UShort? = null,
    @RemainingBytes val payload: P,
) : ControlPacketV4<P>,
    PublishMessage {
    init {
        // §3.3.1-4: Reserved QoS = 3 is malformed.
        if (header.publishQos == 3) {
            throw MalformedPacketException(
                "[MQTT-3.3.1-4] PUBLISH MUST NOT have both QoS bits set to 1.",
            )
        }
    }

    override val controlPacketValue: Byte get() = PublishMessage.CONTROL_PACKET_VALUE
    override val direction: DirectionOfFlow get() = DirectionOfFlow.BIDIRECTIONAL
    override val flags: Byte get() = (header.raw.toInt() and 0x0F).toByte()

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


    override fun expectedResponse(
        reasonCode: ReasonCode,
        reasonString: String?,
        userProperty: List<Pair<String, String>>,
    ): ControlPacket? =
        when (qualityOfService) {
            AT_LEAST_ONCE -> PublishAcknowledgment(packetIdentifier.toUShort())
            EXACTLY_ONCE -> PublishReceived(packetIdentifier.toUShort())
            else -> null
        }

    override fun setDupFlagNewPubMessage(): PublishMessage {
        val rawByte = header.raw.toInt()
        return if (qualityOfService == AT_MOST_ONCE && dup) {
            copy(header = MqttFixedHeader((rawByte and 0xF7).toUByte())) // clear DUP
        } else if (qualityOfService != AT_MOST_ONCE && !dup) {
            copy(header = MqttFixedHeader((rawByte or 0x08).toUByte())) // set DUP
        } else {
            this
        }
    }

    override fun maybeCopyWithNewPacketIdentifier(packetIdentifier: Int): PublishMessage =
        when (qualityOfService) {
            AT_MOST_ONCE -> this
            else -> copy(packetId = packetIdentifier.toUShort())
        }

    override fun validate(): MalformedPacketException? {
        val hasPid = packetId != null
        if (qualityOfService == AT_MOST_ONCE &&
            hasPid &&
            packetIdentifier in validControlPacketIdentifierRange
        ) {
            return MalformedPacketException(
                "[MQTT-2.3.1-5] A PUBLISH Packet MUST NOT contain a Packet Identifier if its QoS" +
                    " value is set to 0.",
            )
        } else if (qualityOfService.isGreaterThan(AT_MOST_ONCE) &&
            (!hasPid || packetIdentifier !in validControlPacketIdentifierRange)
        ) {
            return MalformedPacketException(
                "[MQTT-2.3.1-1] SUBSCRIBE, UNSUBSCRIBE, and PUBLISH (in cases where QoS > 0)" +
                    " Control Packets MUST contain a non-zero 16-bit Packet Identifier.",
            )
        }
        return null
    }

    companion object {
        /**
         * Convenience factory — constructs a `PublishMessageV4<OpaquePublishPayload>` from a
         * raw [ReadBuffer] payload. Pattern #2 (consumer-owned `PlatformBuffer`): allocates
         * a fresh buffer, copies the wire bytes, hands ownership to the handle. Consumers
         * needing a typed payload construct `PublishMessageV4<MyPayload>(...)` directly.
         */
        fun ofRaw(
            topic: TopicName,
            qos: QualityOfService = QualityOfService.AT_MOST_ONCE,
            payload: ReadBuffer? = null,
            dup: Boolean = false,
            retain: Boolean = false,
            packetIdentifier: Int = com.ditchoom.mqtt.controlpacket.NO_PACKET_ID,
        ): PublishMessageV4<OpaquePublishPayload> {
            val type = 3 shl 4
            val dupBit = if (dup) 0x08 else 0
            val qosBits = qos.integerValue.toInt() shl 1
            val retainBit = if (retain) 0x01 else 0
            val header = MqttFixedHeader((type or dupBit or qosBits or retainBit).toUByte())
            val pid =
                if (qos == AT_MOST_ONCE || packetIdentifier == com.ditchoom.mqtt.controlpacket.NO_PACKET_ID) {
                    null
                } else {
                    packetIdentifier.toUShort()
                }
            val factory = BufferFactory.Default
            val remaining = payload?.remaining() ?: 0
            val dst = factory.allocate(remaining)
            if (remaining > 0 && payload != null) dst.write(payload)
            dst.resetForRead()
            val opaque =
                OpaquePublishPayload(
                    com.ditchoom.buffer.codec.opaqueBytesFrom(dst),
                )
            return PublishMessageV4(
                header = header,
                topicName = topic.toString(),
                packetId = pid,
                payload = opaque,
            )
        }
    }
}

// ── PUBACK (§3.4) ─────────────────────────────────────────────────────────

@PacketType(value = 4, wire = 0x40)
@ProtocolMessage
data class PublishAcknowledgment(
    val header: MqttFixedHeader = MqttFixedHeader(0x40u),
    val packetId: UShort,
) : ControlPacketV4<Nothing>,
    IPublishAcknowledgment {
    constructor(packetId: UShort) : this(MqttFixedHeader(0x40u), packetId)

    init {
        if (header.flags != 0) {
            throw MalformedPacketException(
                "Reserved fixed-header flags for PUBACK must be 0x0, got 0x${header.flags.toString(16)}",
            )
        }
    }

    override val packetIdentifier: Int get() = packetId.toInt()
    override val controlPacketValue: Byte get() = IPublishAcknowledgment.CONTROL_PACKET_VALUE
    override val direction: DirectionOfFlow get() = DirectionOfFlow.BIDIRECTIONAL
}

// ── PUBREC (§3.5) ─────────────────────────────────────────────────────────

@PacketType(value = 5, wire = 0x50)
@ProtocolMessage
data class PublishReceived(
    val header: MqttFixedHeader = MqttFixedHeader(0x50u),
    val packetId: UShort,
) : ControlPacketV4<Nothing>,
    IPublishReceived {
    constructor(packetId: UShort) : this(MqttFixedHeader(0x50u), packetId)

    init {
        if (header.flags != 0) {
            throw MalformedPacketException(
                "Reserved fixed-header flags for PUBREC must be 0x0, got 0x${header.flags.toString(16)}",
            )
        }
    }

    override val packetIdentifier: Int get() = packetId.toInt()
    override val controlPacketValue: Byte get() = IPublishReceived.CONTROL_PACKET_VALUE
    override val direction: DirectionOfFlow get() = DirectionOfFlow.BIDIRECTIONAL

    override fun expectedResponse(
        reasonCode: ReasonCode,
        reasonString: String?,
        userProperty: List<Pair<String, String>>,
    ) = PublishRelease(packetId)
}

// ── PUBREL (§3.6) ─────────────────────────────────────────────────────────

@PacketType(value = 6, wire = 0x62)
@ProtocolMessage
data class PublishRelease(
    val header: MqttFixedHeader = MqttFixedHeader(0x62u),
    val packetId: UShort,
) : ControlPacketV4<Nothing>,
    IPublishRelease {
    constructor(packetId: UShort) : this(MqttFixedHeader(0x62u), packetId)

    init {
        // §3.6.1: reserved low-nibble bits MUST be 0010.
        if (header.flags != 0b10) {
            throw MalformedPacketException(
                "Reserved fixed-header flags for PUBREL must be 0x2, got 0x${header.flags.toString(16)}",
            )
        }
    }

    override val packetIdentifier: Int get() = packetId.toInt()
    override val controlPacketValue: Byte get() = IPublishRelease.CONTROL_PACKET_VALUE
    override val direction: DirectionOfFlow get() = DirectionOfFlow.BIDIRECTIONAL
    override val flags: Byte get() = 0b10

    override fun expectedResponse(
        reasonCode: ReasonCode,
        reasonString: String?,
        userProperty: List<Pair<String, String>>,
    ) = PublishComplete(packetId)
}

// ── PUBCOMP (§3.7) ────────────────────────────────────────────────────────

@PacketType(value = 7, wire = 0x70)
@ProtocolMessage
data class PublishComplete(
    val header: MqttFixedHeader = MqttFixedHeader(0x70u),
    val packetId: UShort,
) : ControlPacketV4<Nothing>,
    IPublishComplete {
    constructor(packetId: UShort) : this(MqttFixedHeader(0x70u), packetId)

    init {
        if (header.flags != 0) {
            throw MalformedPacketException(
                "Reserved fixed-header flags for PUBCOMP must be 0x0, got 0x${header.flags.toString(16)}",
            )
        }
    }

    override val packetIdentifier: Int get() = packetId.toInt()
    override val controlPacketValue: Byte get() = IPublishComplete.CONTROL_PACKET_VALUE
    override val direction: DirectionOfFlow get() = DirectionOfFlow.BIDIRECTIONAL
}

// ── SUBSCRIBE (§3.8) ──────────────────────────────────────────────────────

@PacketType(value = 8, wire = 0x82)
@ProtocolMessage
data class SubscribeRequest(
    val header: MqttFixedHeader = MqttFixedHeader(0x82u),
    val packetId: UShort,
    @RemainingBytes val entries: List<SubscriptionEntry>,
) : ControlPacketV4<Nothing>,
    ISubscribeRequest {
    init {
        // §3.8.1: reserved low-nibble bits MUST be 0010.
        if (header.flags != 0b10) {
            throw MalformedPacketException(
                "Reserved fixed-header flags for SUBSCRIBE must be 0x2, got 0x${header.flags.toString(16)}",
            )
        }
    }

    override val packetIdentifier: Int get() = packetId.toInt()
    override val subscriptions: Set<ISubscription> get() = entries.toSet()
    override val controlPacketValue: Byte get() = ISubscribeRequest.CONTROL_PACKET_VALUE
    override val direction: DirectionOfFlow get() = DirectionOfFlow.CLIENT_TO_SERVER
    override val flags: Byte get() = 0b10

    constructor(packetId: UShort, entries: List<SubscriptionEntry>) :
        this(MqttFixedHeader(0x82u), packetId, entries)

    constructor(packetIdentifier: Int, subscriptions: Set<ISubscription>) :
        this(
            MqttFixedHeader(0x82u),
            packetIdentifier.toUShort(),
            subscriptions
                .sortedBy { it.topicFilter.toString() }
                .map { SubscriptionEntry(it.topicFilter.toString(), it.maximumQos.integerValue.toUByte()) },
        )

    constructor(packetIdentifier: UShort, topic: TopicFilter, qos: QualityOfService) :
        this(
            MqttFixedHeader(0x82u),
            packetIdentifier,
            listOf(SubscriptionEntry(topic.toString(), qos.integerValue.toUByte())),
        )

    constructor(packetIdentifier: UShort, topic: String, qos: QualityOfService) :
        this(
            MqttFixedHeader(0x82u),
            packetIdentifier,
            listOf(SubscriptionEntry(topic, qos.integerValue.toUByte())),
        )

    constructor(packetIdentifier: UShort, topics: List<TopicFilter>, qos: List<QualityOfService>) :
        this(
            packetIdentifier.toInt(),
            subscriptions = Subscription.from(topics, qos),
        )

    constructor(packetIdentifier: Int, topicsQosMap: Map<TopicFilter, QualityOfService>) :
        this(
            packetIdentifier,
            subscriptions = Subscription.from(topicsQosMap.keys.toList(), topicsQosMap.values.toList()),
        )

    override fun copyWithNewPacketIdentifier(packetIdentifier: Int): ISubscribeRequest = copy(packetId = packetIdentifier.toUShort())

    override fun expectedResponse(): SubscribeAcknowledgement {
        val returnCodes =
            entries.map {
                when (it.maximumQos) {
                    AT_MOST_ONCE -> GRANTED_QOS_0
                    AT_LEAST_ONCE -> GRANTED_QOS_1
                    EXACTLY_ONCE -> GRANTED_QOS_2
                }
            }
        return SubscribeAcknowledgement(packetId.toInt(), returnCodes)
    }
}

// ── SUBACK (§3.9) ─────────────────────────────────────────────────────────

@PacketType(value = 9, wire = 0x90)
@ProtocolMessage
data class SubscribeAcknowledgement(
    val header: MqttFixedHeader = MqttFixedHeader(0x90u),
    val packetId: UShort,
    @RemainingBytes val returnCodes: List<SubAckReturnCode>,
) : ControlPacketV4<Nothing>,
    ISubscribeAcknowledgement {
    init {
        if (header.flags != 0) {
            throw MalformedPacketException(
                "Reserved fixed-header flags for SUBACK must be 0x0, got 0x${header.flags.toString(16)}",
            )
        }
    }

    override val packetIdentifier: Int get() = packetId.toInt()
    override val controlPacketValue: Byte get() = ISubscribeAcknowledgement.CONTROL_PACKET_VALUE
    override val direction: DirectionOfFlow get() = DirectionOfFlow.SERVER_TO_CLIENT

    constructor(packetId: UShort, returnCodes: List<SubAckReturnCode>) :
        this(MqttFixedHeader(0x90u), packetId, returnCodes)

    /** Convenience constructor from domain-level [ReasonCode] list. */
    constructor(packetIdentifier: Int, payload: List<ReasonCode>) :
        this(
            MqttFixedHeader(0x90u),
            packetIdentifier.toUShort(),
            payload.map { SubAckReturnCode.fromByte(it.byte) },
        )

    val payload: List<ReasonCode>
        get() =
            returnCodes.map { rc ->
                when (rc) {
                    SubAckReturnCode.SuccessMaximumQoS0 -> GRANTED_QOS_0
                    SubAckReturnCode.SuccessMaximumQoS1 -> GRANTED_QOS_1
                    SubAckReturnCode.SuccessMaximumQoS2 -> GRANTED_QOS_2
                    SubAckReturnCode.Failure -> UNSPECIFIED_ERROR
                }
            }
}

// ── UNSUBSCRIBE (§3.10) ───────────────────────────────────────────────────

@PacketType(value = 10, wire = 0xA2)
@ProtocolMessage
data class UnsubscribeRequest(
    val header: MqttFixedHeader = MqttFixedHeader(0xA2u),
    val packetId: UShort,
    @RemainingBytes val topicEntries: List<TopicFilterEntry>,
) : ControlPacketV4<Nothing>,
    IUnsubscribeRequest {
    init {
        // §3.10.1: reserved low-nibble bits MUST be 0010.
        if (header.flags != 0b10) {
            throw MalformedPacketException(
                "Reserved fixed-header flags for UNSUBSCRIBE must be 0x2, got 0x${header.flags.toString(16)}",
            )
        }
        if (topicEntries.isEmpty()) {
            throw ProtocolError("An UNSUBSCRIBE packet with no Payload is a Protocol Error")
        }
    }

    override val packetIdentifier: Int get() = packetId.toInt()
    override val topics: Set<TopicFilter> get() = topicEntries.map { TopicFilter.fromOrThrow(it.filter) }.toSet()
    override val controlPacketValue: Byte get() = IUnsubscribeRequest.controlPacketValue
    override val direction: DirectionOfFlow get() = DirectionOfFlow.CLIENT_TO_SERVER
    override val flags: Byte get() = 0b10

    constructor(packetId: UShort, topicEntries: List<TopicFilterEntry>) :
        this(MqttFixedHeader(0xA2u), packetId, topicEntries)

    constructor(packetIdentifier: Int, topics: Set<TopicFilter>) :
        this(MqttFixedHeader(0xA2u), packetIdentifier.toUShort(), topics.map { TopicFilterEntry(it.toString()) })

    constructor(packetIdentifier: Int, topicString: Collection<String>) :
        this(MqttFixedHeader(0xA2u), packetIdentifier.toUShort(), topicString.map { TopicFilterEntry(it) })

    override fun copyWithNewPacketIdentifier(packetIdentifier: Int): IUnsubscribeRequest = copy(packetId = packetIdentifier.toUShort())
}

// ── UNSUBACK (§3.11) ──────────────────────────────────────────────────────

@PacketType(value = 11, wire = 0xB0)
@ProtocolMessage
data class UnsubscribeAcknowledgment(
    val header: MqttFixedHeader = MqttFixedHeader(0xB0u),
    val packetId: UShort,
) : ControlPacketV4<Nothing>,
    IUnsubscribeAcknowledgment {
    constructor(packetId: UShort) : this(MqttFixedHeader(0xB0u), packetId)

    init {
        if (header.flags != 0) {
            throw MalformedPacketException(
                "Reserved fixed-header flags for UNSUBACK must be 0x0, got 0x${header.flags.toString(16)}",
            )
        }
    }

    override val packetIdentifier: Int get() = packetId.toInt()
    override val controlPacketValue: Byte get() = IUnsubscribeAcknowledgment.CONTROL_PACKET_VALUE
    override val direction: DirectionOfFlow get() = DirectionOfFlow.SERVER_TO_CLIENT
}

// ── PINGREQ / PINGRESP / DISCONNECT (no body) ─────────────────────────────

/**
 * 3.12 PINGREQ — PING request. No body on the wire (`0xC0 0x00`). Modeled as a `data class`
 * with a defaulted [header] so the dispatcher's variant-codec path treats it like every
 * other variant (carries the discriminator field, encodes via the @FramedBy parent).
 */
@PacketType(value = 12, wire = 0xC0)
@ProtocolMessage
data class PingRequest(
    val header: MqttFixedHeader = MqttFixedHeader(0xC0u),
) : ControlPacketV4<Nothing>,
    IPingRequest {
    override val controlPacketValue: Byte get() = 12
    override val direction: DirectionOfFlow get() = DirectionOfFlow.CLIENT_TO_SERVER
}

@PacketType(value = 13, wire = 0xD0)
@ProtocolMessage
data class PingResponse(
    val header: MqttFixedHeader = MqttFixedHeader(0xD0u),
) : ControlPacketV4<Nothing>,
    IPingResponse {
    override val controlPacketValue: Byte get() = 13
    override val direction: DirectionOfFlow get() = DirectionOfFlow.SERVER_TO_CLIENT
}

@PacketType(value = 14, wire = 0xE0)
@ProtocolMessage
data class DisconnectNotification(
    val header: MqttFixedHeader = MqttFixedHeader(0xE0u),
) : ControlPacketV4<Nothing>,
    IDisconnectNotification {
    override val controlPacketValue: Byte get() = 14
    override val direction: DirectionOfFlow get() = DirectionOfFlow.BIDIRECTIONAL
}

// ── Backward-compat lookup table for ConnectionAcknowledgment ─────────────

val connackReturnCode by lazy(LazyThreadSafetyMode.NONE) {
    mapOf(
        ConnectionAcknowledgment.VariableHeader.ReturnCode.CONNECTION_ACCEPTED.value to
            ConnectionAcknowledgment.VariableHeader.ReturnCode.CONNECTION_ACCEPTED,
        ConnectionAcknowledgment.VariableHeader.ReturnCode.CONNECTION_REFUSED_UNACCEPTABLE_PROTOCOL_VERSION.value to
            ConnectionAcknowledgment.VariableHeader.ReturnCode.CONNECTION_REFUSED_UNACCEPTABLE_PROTOCOL_VERSION,
        ConnectionAcknowledgment.VariableHeader.ReturnCode.CONNECTION_REFUSED_IDENTIFIER_REJECTED.value to
            ConnectionAcknowledgment.VariableHeader.ReturnCode.CONNECTION_REFUSED_IDENTIFIER_REJECTED,
        ConnectionAcknowledgment.VariableHeader.ReturnCode.CONNECTION_REFUSED_SERVER_UNAVAILABLE.value to
            ConnectionAcknowledgment.VariableHeader.ReturnCode.CONNECTION_REFUSED_SERVER_UNAVAILABLE,
        ConnectionAcknowledgment.VariableHeader.ReturnCode.CONNECTION_REFUSED_BAD_USER_NAME_OR_PASSWORD.value to
            ConnectionAcknowledgment.VariableHeader.ReturnCode.CONNECTION_REFUSED_BAD_USER_NAME_OR_PASSWORD,
        ConnectionAcknowledgment.VariableHeader.ReturnCode.CONNECTION_REFUSED_NOT_AUTHORIZED.value to
            ConnectionAcknowledgment.VariableHeader.ReturnCode.CONNECTION_REFUSED_NOT_AUTHORIZED,
        ConnectionAcknowledgment.VariableHeader.ReturnCode.RESERVED.value to
            ConnectionAcknowledgment.VariableHeader.ReturnCode.CONNECTION_REFUSED_NOT_AUTHORIZED,
    )
}

// ── Subscription helper (legacy) ──────────────────────────────────────────

/**
 * Legacy helper for constructing subscription sets from lists.
 * Used by convenience constructors.
 */
data class Subscription(
    override val topicFilter: TopicFilter,
    override val maximumQos: QualityOfService = AT_LEAST_ONCE,
) : ISubscription {
    companion object {
        fun from(
            topics: List<TopicFilter>,
            qos: List<QualityOfService>,
        ): Set<ISubscription> {
            if (topics.size != qos.size) {
                throw ProtocolError(
                    "[MQTT-3.8.3-3] The payload of a SUBSCRIBE packet MUST contain at least one Topic Filter / QoS pair. A SUBSCRIBE packet with no payload is a protocol violation.",
                )
            }
            val subscriptions = mutableSetOf<ISubscription>()
            topics.forEachIndexed { index, topic ->
                subscriptions += Subscription(topic, qos[index])
            }
            return subscriptions
        }

        fun fromOrThrow(
            topics: List<String>,
            qos: List<QualityOfService>,
        ): Set<ISubscription> {
            if (topics.size != qos.size) {
                throw ProtocolError(
                    "[MQTT-3.8.3-3] The payload of a SUBSCRIBE packet MUST contain at least one Topic Filter / QoS pair. A SUBSCRIBE packet with no payload is a protocol violation.",
                )
            }
            val subscriptions = mutableSetOf<ISubscription>()
            topics.forEachIndexed { index, topic ->
                subscriptions += Subscription(TopicFilter.fromOrThrow(topic), qos[index])
            }
            return subscriptions
        }
    }
}
