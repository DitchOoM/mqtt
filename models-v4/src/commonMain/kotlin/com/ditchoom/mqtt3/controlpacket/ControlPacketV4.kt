package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.annotations.DispatchOn
import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.PacketTypeRange
import com.ditchoom.buffer.codec.annotations.Payload
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.RemainingBytes
import com.ditchoom.buffer.codec.annotations.WhenTrue
import com.ditchoom.mqtt.MalformedPacketException
import com.ditchoom.mqtt.MqttWarning
import com.ditchoom.mqtt.ProtocolError
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.readVariableByteInteger
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
import com.ditchoom.mqtt.controlpacket.NO_PACKET_ID
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
 * Wire model for a single SUBACK return code byte.
 */
@ProtocolMessage
@JvmInline
value class SubAckReturnCode(
    val raw: UByte,
)

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
@ProtocolMessage(onUnknownDiscriminator = "com.ditchoom.mqtt.MalformedPacketException")
sealed interface ControlPacketV4 : ControlPacket {
    override val mqttVersion: Byte get() = 4
    override val controlPacketFactory: ControlPacketFactory get() = ControlPacketV4Factory

    companion object {
        /**
         * Decode a full v4 control-packet wire (`[byte1][VBI(remainingLength)][body]`).
         *
         * Reads byte1 + VBI, validates the per-type body-length contract, then directly
         * dispatches to the matching variant codec with the fixed header pre-supplied via
         * [ControlPacketV4Codec.DiscriminatorKey]. Variant codecs read the header from
         * context (no extra buffer read) and consume the body bytes left in [buffer].
         *
         * Zero-copy: the dispatcher does NOT reframe `[byte1][body]` into a fresh buffer.
         * The slice passed in is consumed directly — body bytes are read in place.
         */
        fun from(buffer: ReadBuffer): ControlPacketV4 {
            val byte1 = buffer.readUnsignedByte()
            val remainingLength = buffer.readVariableByteInteger()
            val packetType = (byte1.toUInt() shr 4).toInt()
            // PINGREQ / PINGRESP / DISCONNECT have no body — variant codecs read zero bytes,
            // and the dispatcher does not body-frame at the top level (no `bodyLength` on
            // `@DispatchOn(MqttFixedHeader::class)`), so a non-zero remainingLength would
            // silently leave trailing bytes in the buffer. Validate up-front.
            if ((packetType == 12 || packetType == 13 || packetType == 14) && remainingLength != 0) {
                throw MalformedPacketException(
                    "Reserved fixed-header flags or non-zero remaining length for packet type " +
                        "$packetType (expected 0, got $remainingLength)",
                )
            }
            // Validate the fixed header byte itself (e.g. PUBLISH QoS=3 → MalformedPacketException)
            // before any variant codec reads body bytes.
            val header = MqttFixedHeader(byte1)
            val ctx =
                com.ditchoom.buffer.codec.DecodeContext.Empty
                    .with(ControlPacketV4Codec.DiscriminatorKey, header)
                    .with(ConnectionRequestCodec.WillPayloadValueDecodeKey) { slice ->
                        if (slice.remaining() > 0) slice else null
                    }.with(PublishMessageV4Codec.PayloadDecodeKey) { slice -> slice }
            // PUBLISH dispatches by raw byte (top nibble 3, low nibble = dup/qos/retain).
            val rawByte = byte1.toInt() and 0xFF
            return when {
                rawByte in 0x30..0x3F -> PublishMessageV4Codec.decodeFromContext(buffer, ctx)
                packetType == 0 -> ReservedCodec.decode(buffer, ctx)
                packetType == 1 -> ConnectionRequestCodec.decodeFromContext(buffer, ctx)
                packetType == 2 -> ConnectionAcknowledgmentCodec.decode(buffer, ctx)
                packetType == 4 -> PublishAcknowledgmentCodec.decode(buffer, ctx)
                packetType == 5 -> PublishReceivedCodec.decode(buffer, ctx)
                packetType == 6 -> PublishReleaseCodec.decode(buffer, ctx)
                packetType == 7 -> PublishCompleteCodec.decode(buffer, ctx)
                packetType == 8 -> SubscribeRequestCodec.decode(buffer, ctx)
                packetType == 9 -> SubscribeAcknowledgementCodec.decode(buffer, ctx)
                packetType == 10 -> UnsubscribeRequestCodec.decode(buffer, ctx)
                packetType == 11 -> UnsubscribeAcknowledgmentCodec.decode(buffer, ctx)
                packetType == 12 -> PingRequestCodec.decode(buffer, ctx)
                packetType == 13 -> PingResponseCodec.decode(buffer, ctx)
                packetType == 14 -> DisconnectNotificationCodec.decode(buffer, ctx)
                else -> throw MalformedPacketException(
                    "Unknown discriminator: 0x${rawByte.toString(16)}",
                )
            }
        }
    }
}

// ── Reserved (wire 0x00) ───────────────────────────────────────────────────

@PacketType(wire = 0)
@ProtocolMessage
data object Reserved : ControlPacketV4 {
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
@PacketType(wire = 1)
@ProtocolMessage
data class ConnectionRequest<@Payload WP>(
    val fixedHeader: MqttFixedHeader = MqttFixedHeader(0x10u),
    @LengthPrefixed override val protocolName: String = "MQTT",
    val protocolLevel: UByte = 4u,
    val connectFlags: ConnectV4Flags = ConnectV4Flags(0u),
    val keepAlive: UShort = UShort.MAX_VALUE,
    @LengthPrefixed val clientId: String = "",
    @WhenTrue("connectFlags.willFlag") @LengthPrefixed val willTopicString: String? = null,
    @WhenTrue("connectFlags.willFlag") @LengthPrefixed val willPayloadValue: WP? = null,
    @WhenTrue("connectFlags.usernameFlag") @LengthPrefixed val username: String? = null,
    @WhenTrue("connectFlags.passwordFlag") @LengthPrefixed override val password: String? = null,
) : ControlPacketV4,
    IConnectionRequest {
    init {
        if (fixedHeader.flags != 0) {
            throw MalformedPacketException(
                "Reserved fixed-header flags for CONNECT must be 0x0, got 0x${fixedHeader.flags.toString(16)}",
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

    override val will: WillConfig
        get() {
            val payload = willPayloadValue
            return if (
                connectFlags.willFlag &&
                willTopicString != null &&
                payload is ReadBuffer
            ) {
                WillConfig.Enabled(
                    TopicName.fromOrThrow(willTopicString),
                    payload as ReadBuffer,
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
                willPayload = willPayloadValue as? ReadBuffer,
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

    override fun encodeBody(writeBuffer: WriteBuffer) {
        @Suppress("UNCHECKED_CAST")
        ConnectionRequestCodec.encodeBody(writeBuffer, this as ConnectionRequest<ReadBuffer?>) { buf, wp ->
            if (wp != null) buf.write(wp)
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun remainingLength(): Int =
        ConnectionRequestCodec.wireSizeBody(this as ConnectionRequest<ReadBuffer?>) { wp ->
            (wp as? ReadBuffer)?.remaining() ?: 0
        }

    // ── Legacy convenience constructors ──

    @Suppress("UNCHECKED_CAST")
    constructor(
        variableHeader: VariableHeader,
        payload: Payload = Payload(),
    ) : this(
        fixedHeader = MqttFixedHeader(0x10u),
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
        willPayloadValue = payload.willPayload as WP?,
        username = payload.userName,
        password = payload.password,
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
 * Wire-shape data class. The wire `MqttFixedHeader` is named `fixedHeader` so the legacy
 * `header: VariableHeader` accessor (used by tests) is preserved.
 */
@PacketType(wire = 2)
@ProtocolMessage
data class ConnectionAcknowledgment(
    val fixedHeader: MqttFixedHeader = MqttFixedHeader(0x20u),
    val acknowledgeFlags: UByte = 0u,
    val returnCode: UByte = 0u,
) : ControlPacketV4,
    IConnectionAcknowledgment {
    init {
        if (fixedHeader.flags != 0) {
            throw MalformedPacketException(
                "Reserved fixed-header flags for CONNACK must be 0x0, got 0x${fixedHeader.flags.toString(16)}",
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
    override val isSuccessful: Boolean get() = header.connectReason == VariableHeader.ReturnCode.CONNECTION_ACCEPTED
    override val connectionReason: String get() = header.connectReason.name

    /** Legacy `header: VariableHeader` accessor (preserves the pre-migration API). */
    val header: VariableHeader
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

    constructor(header: VariableHeader = VariableHeader()) : this(
        fixedHeader = MqttFixedHeader(0x20u),
        acknowledgeFlags = (if (header.sessionPresent) 1u else 0u).toUByte(),
        returnCode = header.connectReason.value,
    )

    constructor(sessionPresent: Boolean, connectReason: VariableHeader.ReturnCode) :
        this(VariableHeader(sessionPresent, connectReason))

    override fun encodeBody(writeBuffer: WriteBuffer) =
        ConnectionAcknowledgmentCodec.encodeBody(writeBuffer, this)

    override fun remainingLength(): Int = ConnectionAcknowledgmentCodec.wireSizeBody(this)

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

// Factory functions matching legacy `ConnectionRequest(...)` call shapes. The class itself
// is generic over the will-payload type `<@Payload WP>` (codec processor requirement for
// embedding ReadBuffer); these factories bind `WP = ReadBuffer?` so test/production call
// sites that don't supply a type argument keep compiling.

@Suppress("FunctionName")
fun ConnectionRequest(
    variableHeader: ConnectionRequest.VariableHeader = ConnectionRequest.VariableHeader(),
    payload: ConnectionRequest.Payload = ConnectionRequest.Payload(),
): ConnectionRequest<ReadBuffer?> = ConnectionRequest<ReadBuffer?>(variableHeader, payload)

@Suppress("FunctionName")
fun ConnectionRequest(
    clientId: String,
    keepAliveSeconds: Int = 3600,
    cleanSession: Boolean = false,
    userName: String? = null,
    password: String? = null,
    will: WillConfig = WillConfig.Disabled,
    protocolName: String = "MQTT",
    protocolLevel: UByte = 4u,
): ConnectionRequest<ReadBuffer?> =
    ConnectionRequest<ReadBuffer?>(
        clientId,
        keepAliveSeconds,
        cleanSession,
        userName,
        password,
        will,
        protocolName,
        protocolLevel,
    )

// ── PUBLISH (§3.3) ────────────────────────────────────────────────────────

/**
 * MQTT 3.1.1 PUBLISH (§3.3). Wire-shape data class; typed accessors on the [PublishMessage]
 * interface delegate to fields decoded from the fixed-header byte.
 *
 * The `<@Payload P>` type parameter enables zero-copy slice forwarding on decode and
 * caller-supplied encoding on encode. Production callers use `PublishMessageV4<ReadBuffer>`.
 */
@PacketTypeRange(0x30, 0x3F)
@ProtocolMessage
data class PublishMessageV4<@Payload P>(
    val header: MqttFixedHeader,
    @LengthPrefixed val topicName: String,
    @WhenTrue("header.publishHasPacketIdentifier") val packetId: UShort? = null,
    @RemainingBytes val payload: P,
) : ControlPacketV4,
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

    override fun rawPayload(): ReadBuffer = (payload as? ReadBuffer) ?: BufferFactory.Default.allocate(0)

    override fun encodeBody(writeBuffer: WriteBuffer) {
        @Suppress("UNCHECKED_CAST")
        PublishMessageV4Codec.encodeBody(
            writeBuffer,
            this as PublishMessageV4<ReadBuffer>,
        ) { buf, p -> buf.write(p) }
    }

    @Suppress("UNCHECKED_CAST")
    override fun remainingLength(): Int =
        PublishMessageV4Codec.wireSizeBody(this as PublishMessageV4<ReadBuffer>) { p -> p.remaining() }

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
        if (qualityOfService == AT_MOST_ONCE && hasPid &&
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
         * Create a v4 PUBLISH with a raw-bytes payload. Null falls back to an empty buffer.
         */
        fun ofRaw(
            topic: TopicName,
            qos: QualityOfService = AT_MOST_ONCE,
            payload: ReadBuffer? = null,
            dup: Boolean = false,
            retain: Boolean = false,
            packetIdentifier: Int = NO_PACKET_ID,
        ): PublishMessageV4<ReadBuffer> {
            val header = MqttFixedHeader(makePublishHeaderByte(dup, qos, retain))
            // Preserve the user-supplied packetIdentifier even when qos=0 / packetIdentifier=NO_PACKET_ID
            // so `validate()` can detect malformed combinations (qos=0 + non-zero pid;
            // qos>0 + missing pid). Decoded messages from the wire reach the wire-shape
            // primary constructor directly with packetId=null when qos=0.
            val pid = if (packetIdentifier == NO_PACKET_ID) null else packetIdentifier.toUShort()
            return PublishMessageV4(
                header = header,
                topicName = topic.toString(),
                packetId = pid,
                payload = payload ?: BufferFactory.Default.allocate(0),
            )
        }

        /**
         * Create a v4 PUBLISH with a typed payload. Eagerly encodes [payload] via
         * [encodePayload] into a `ReadBuffer`; the message carries the encoded bytes.
         */
        fun <P> ofTyped(
            topic: TopicName,
            qos: QualityOfService,
            payload: P,
            encodePayload: WriteBuffer.(P) -> Unit,
            dup: Boolean = false,
            retain: Boolean = false,
            packetIdentifier: Int = NO_PACKET_ID,
        ): PublishMessageV4<ReadBuffer> = ofRaw(topic, qos, eagerEncode(payload, encodePayload), dup, retain, packetIdentifier)

        private fun <P> eagerEncode(
            value: P,
            encodePayload: WriteBuffer.(P) -> Unit,
        ): ReadBuffer =
            com.ditchoom.buffer.codec
                .encodeWithGrowth { it.encodePayload(value) }

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

// ── PUBACK (§3.4) ─────────────────────────────────────────────────────────

@PacketType(wire = 4)
@ProtocolMessage
data class PublishAcknowledgment(
    val header: MqttFixedHeader = MqttFixedHeader(0x40u),
    val packetId: UShort,
) : ControlPacketV4,
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

    override fun encodeBody(writeBuffer: WriteBuffer) =
        PublishAcknowledgmentCodec.encodeBody(writeBuffer, this)

    override fun remainingLength() = PublishAcknowledgmentCodec.wireSizeBody(this)
}

// ── PUBREC (§3.5) ─────────────────────────────────────────────────────────

@PacketType(wire = 5)
@ProtocolMessage
data class PublishReceived(
    val header: MqttFixedHeader = MqttFixedHeader(0x50u),
    val packetId: UShort,
) : ControlPacketV4,
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

    override fun encodeBody(writeBuffer: WriteBuffer) =
        PublishReceivedCodec.encodeBody(writeBuffer, this)

    override fun remainingLength() = PublishReceivedCodec.wireSizeBody(this)

    override fun expectedResponse(
        reasonCode: ReasonCode,
        reasonString: String?,
        userProperty: List<Pair<String, String>>,
    ) = PublishRelease(packetId)
}

// ── PUBREL (§3.6) ─────────────────────────────────────────────────────────

@PacketType(wire = 6)
@ProtocolMessage
data class PublishRelease(
    val header: MqttFixedHeader = MqttFixedHeader(0x62u),
    val packetId: UShort,
) : ControlPacketV4,
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

    override fun encodeBody(writeBuffer: WriteBuffer) =
        PublishReleaseCodec.encodeBody(writeBuffer, this)

    override fun remainingLength() = PublishReleaseCodec.wireSizeBody(this)

    override fun expectedResponse(
        reasonCode: ReasonCode,
        reasonString: String?,
        userProperty: List<Pair<String, String>>,
    ) = PublishComplete(packetId)
}

// ── PUBCOMP (§3.7) ────────────────────────────────────────────────────────

@PacketType(wire = 7)
@ProtocolMessage
data class PublishComplete(
    val header: MqttFixedHeader = MqttFixedHeader(0x70u),
    val packetId: UShort,
) : ControlPacketV4,
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

    override fun encodeBody(writeBuffer: WriteBuffer) =
        PublishCompleteCodec.encodeBody(writeBuffer, this)

    override fun remainingLength() = PublishCompleteCodec.wireSizeBody(this)
}

// ── SUBSCRIBE (§3.8) ──────────────────────────────────────────────────────

@PacketType(wire = 8)
@ProtocolMessage
data class SubscribeRequest(
    val header: MqttFixedHeader = MqttFixedHeader(0x82u),
    val packetId: UShort,
    @RemainingBytes val entries: List<SubscriptionEntry>,
) : ControlPacketV4,
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

    override fun copyWithNewPacketIdentifier(packetIdentifier: Int): ISubscribeRequest =
        copy(packetId = packetIdentifier.toUShort())

    override fun encodeBody(writeBuffer: WriteBuffer) =
        SubscribeRequestCodec.encodeBody(writeBuffer, this)

    override fun remainingLength() = SubscribeRequestCodec.wireSizeBody(this)

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

@PacketType(wire = 9)
@ProtocolMessage
data class SubscribeAcknowledgement(
    val header: MqttFixedHeader = MqttFixedHeader(0x90u),
    val packetId: UShort,
    @RemainingBytes val returnCodes: List<SubAckReturnCode>,
) : ControlPacketV4,
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
            payload.map { SubAckReturnCode(it.byte) },
        )

    val payload: List<ReasonCode>
        get() =
            returnCodes.map { rc ->
                when (rc.raw) {
                    GRANTED_QOS_0.byte -> GRANTED_QOS_0
                    GRANTED_QOS_1.byte -> GRANTED_QOS_1
                    GRANTED_QOS_2.byte -> GRANTED_QOS_2
                    UNSPECIFIED_ERROR.byte -> UNSPECIFIED_ERROR
                    else -> throw MalformedPacketException("Invalid return code ${rc.raw}")
                }
            }

    override fun encodeBody(writeBuffer: WriteBuffer) =
        SubscribeAcknowledgementCodec.encodeBody(writeBuffer, this)

    override fun remainingLength() = SubscribeAcknowledgementCodec.wireSizeBody(this)
}

// ── UNSUBSCRIBE (§3.10) ───────────────────────────────────────────────────

@PacketType(wire = 10)
@ProtocolMessage
data class UnsubscribeRequest(
    val header: MqttFixedHeader = MqttFixedHeader(0xA2u),
    val packetId: UShort,
    @RemainingBytes val topicEntries: List<TopicFilterEntry>,
) : ControlPacketV4,
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

    override fun encodeBody(writeBuffer: WriteBuffer) =
        UnsubscribeRequestCodec.encodeBody(writeBuffer, this)

    override fun remainingLength() = UnsubscribeRequestCodec.wireSizeBody(this)

    override fun copyWithNewPacketIdentifier(packetIdentifier: Int): IUnsubscribeRequest =
        copy(packetId = packetIdentifier.toUShort())
}

// ── UNSUBACK (§3.11) ──────────────────────────────────────────────────────

@PacketType(wire = 11)
@ProtocolMessage
data class UnsubscribeAcknowledgment(
    val header: MqttFixedHeader = MqttFixedHeader(0xB0u),
    val packetId: UShort,
) : ControlPacketV4,
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

    override fun encodeBody(writeBuffer: WriteBuffer) =
        UnsubscribeAcknowledgmentCodec.encodeBody(writeBuffer, this)

    override fun remainingLength() = UnsubscribeAcknowledgmentCodec.wireSizeBody(this)
}

// ── PINGREQ / PINGRESP / DISCONNECT (no body) ─────────────────────────────

/**
 * 3.12 PINGREQ — PING request. No body. Kept as `data object` so legacy expression-style
 * references (`PingRequest`, `packetBuffer { PingRequest }`) keep compiling unchanged.
 */
@PacketType(wire = 12)
@ProtocolMessage
data object PingRequest : ControlPacketV4, IPingRequest {
    override val controlPacketValue: Byte get() = 12
    override val direction: DirectionOfFlow get() = DirectionOfFlow.CLIENT_TO_SERVER

    override fun serialize(writeBuffer: WriteBuffer) {
        writeBuffer.writeShort(0xC000.toShort())
    }
}

@PacketType(wire = 13)
@ProtocolMessage
data object PingResponse : ControlPacketV4, IPingResponse {
    override val controlPacketValue: Byte get() = 13
    override val direction: DirectionOfFlow get() = DirectionOfFlow.SERVER_TO_CLIENT

    override fun serialize(writeBuffer: WriteBuffer) {
        writeBuffer.writeShort(0xD000.toShort())
    }
}

@PacketType(wire = 14)
@ProtocolMessage
data object DisconnectNotification : ControlPacketV4, IDisconnectNotification {
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
