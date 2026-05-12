package com.ditchoom.mqtt5.controlpacket.properties

import com.ditchoom.buffer.codec.annotations.DispatchOn
import com.ditchoom.buffer.codec.annotations.DispatchValue
import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.UseCodec
import com.ditchoom.mqtt.controlpacket.VariableByteIntegerCodec
import kotlin.jvm.JvmInline

/**
 * Discriminator for MQTT v5 property types.
 * The identifier byte as defined in the MQTT 5.0 spec §2.2.2.
 */
@JvmInline
@ProtocolMessage
value class PropertyId(
    val raw: UByte,
) {
    @DispatchValue
    val id: Int get() = raw.toInt()
}

/**
 * MQTT v5 Property sealed interface with codec-generated dispatch.
 *
 * Each variant is a `data class` carrying its [PropertyId] discriminator as the first
 * field (Phase B requirement — the directional-codec processor rejects value-class
 * variants and requires the discriminator field on every data-class variant). The
 * default value of `id` matches the `@PacketType` byte so callers construct properties
 * with named args: `PayloadFormatIndicator(isUtf8 = true)`.
 *
 * Wire format per property: 1-byte identifier + type-specific payload.
 */
@DispatchOn(PropertyId::class)
@ProtocolMessage
sealed interface MqttProperty

// ── Boolean properties (1-byte identifier + 1-byte value) ───────────────

@PacketType(0x01)
@ProtocolMessage
data class PayloadFormatIndicator(
    val id: PropertyId = PropertyId(0x01u),
    val isUtf8: Boolean,
) : MqttProperty

@PacketType(0x17)
@ProtocolMessage
data class RequestProblemInformation(
    val id: PropertyId = PropertyId(0x17u),
    val enabled: Boolean,
) : MqttProperty

@PacketType(0x19)
@ProtocolMessage
data class RequestResponseInformation(
    val id: PropertyId = PropertyId(0x19u),
    val enabled: Boolean,
) : MqttProperty

@PacketType(0x24)
@ProtocolMessage
data class MaximumQos(
    val id: PropertyId = PropertyId(0x24u),
    val qos1Allowed: Boolean,
) : MqttProperty

@PacketType(0x25)
@ProtocolMessage
data class RetainAvailable(
    val id: PropertyId = PropertyId(0x25u),
    val supported: Boolean,
) : MqttProperty

@PacketType(0x28)
@ProtocolMessage
data class WildcardSubscriptionAvailable(
    val id: PropertyId = PropertyId(0x28u),
    val supported: Boolean,
) : MqttProperty

@PacketType(0x29)
@ProtocolMessage
data class SubscriptionIdentifierAvailable(
    val id: PropertyId = PropertyId(0x29u),
    val supported: Boolean,
) : MqttProperty

@PacketType(0x2A)
@ProtocolMessage
data class SharedSubscriptionAvailable(
    val id: PropertyId = PropertyId(0x2Au),
    val supported: Boolean,
) : MqttProperty

// ── Two-byte integer properties (1-byte identifier + UShort) ────────────

@PacketType(0x21)
@ProtocolMessage
data class ReceiveMaximum(
    val id: PropertyId = PropertyId(0x21u),
    val max: UShort,
) : MqttProperty

@PacketType(0x22)
@ProtocolMessage
data class TopicAlias(
    val id: PropertyId = PropertyId(0x22u),
    val value: UShort,
) : MqttProperty

@PacketType(0x23)
@ProtocolMessage
data class TopicAliasMaximum(
    val id: PropertyId = PropertyId(0x23u),
    val max: UShort,
) : MqttProperty

@PacketType(0x13)
@ProtocolMessage
data class ServerKeepAlive(
    val id: PropertyId = PropertyId(0x13u),
    val seconds: UShort,
) : MqttProperty

// ── Four-byte integer properties (1-byte identifier + UInt) ─────────────

@PacketType(0x02)
@ProtocolMessage
data class MessageExpiryInterval(
    val id: PropertyId = PropertyId(0x02u),
    val seconds: UInt,
) : MqttProperty

@PacketType(0x11)
@ProtocolMessage
data class SessionExpiryInterval(
    val id: PropertyId = PropertyId(0x11u),
    val seconds: UInt,
) : MqttProperty

@PacketType(0x18)
@ProtocolMessage
data class WillDelayInterval(
    val id: PropertyId = PropertyId(0x18u),
    val seconds: UInt,
) : MqttProperty

@PacketType(0x27)
@ProtocolMessage
data class MaximumPacketSize(
    val id: PropertyId = PropertyId(0x27u),
    val bytes: UInt,
) : MqttProperty

// ── UTF-8 string properties (1-byte identifier + length-prefixed string) ─

@PacketType(0x03)
@ProtocolMessage
data class ContentType(
    val id: PropertyId = PropertyId(0x03u),
    @LengthPrefixed val value: String,
) : MqttProperty

@PacketType(0x08)
@ProtocolMessage
data class ResponseTopic(
    val id: PropertyId = PropertyId(0x08u),
    @LengthPrefixed val value: String,
) : MqttProperty

@PacketType(0x12)
@ProtocolMessage
data class AssignedClientIdentifier(
    val id: PropertyId = PropertyId(0x12u),
    @LengthPrefixed val value: String,
) : MqttProperty

@PacketType(0x15)
@ProtocolMessage
data class AuthenticationMethod(
    val id: PropertyId = PropertyId(0x15u),
    @LengthPrefixed val value: String,
) : MqttProperty

@PacketType(0x1A)
@ProtocolMessage
data class ResponseInformation(
    val id: PropertyId = PropertyId(0x1Au),
    @LengthPrefixed val value: String,
) : MqttProperty

@PacketType(0x1C)
@ProtocolMessage
data class ServerReference(
    val id: PropertyId = PropertyId(0x1Cu),
    @LengthPrefixed val value: String,
) : MqttProperty

@PacketType(0x1F)
@ProtocolMessage
data class ReasonString(
    val id: PropertyId = PropertyId(0x1Fu),
    @LengthPrefixed val value: String,
) : MqttProperty

// ── UTF-8 string pair (1-byte identifier + two length-prefixed strings) ──

@PacketType(0x26)
@ProtocolMessage
data class UserProperty(
    val id: PropertyId = PropertyId(0x26u),
    @LengthPrefixed val key: String,
    @LengthPrefixed val value: String,
) : MqttProperty

// ── Variable byte integer property ──────────────────────────────────────

@PacketType(0x0B)
@ProtocolMessage
data class SubscriptionIdentifier(
    val id: PropertyId = PropertyId(0x0Bu),
    @UseCodec(VariableByteIntegerCodec::class) val value: UInt,
) : MqttProperty

// ── Binary data properties (Phase A intermediary: UTF-8 string slot) ────

// TODO(buffer-v1): CorrelationData is bytes per MQTT v5 §3.3.2.3.6, not UTF-8.
//  Phase A intermediates through `@LengthPrefixed val value: String`, mirroring the
//  v4 willPayloadValue / passwordValue deferral. UTF-8 decode is lossy for non-UTF-8
//  application correlation tokens. Phase B picks between multi-param parent threading
//  vs hand-written codec vs field-level @InjectedCodec. See memory
//  `mqtt_will_password_deferred.md`.
@PacketType(0x09)
@ProtocolMessage
data class CorrelationData(
    val id: PropertyId = PropertyId(0x09u),
    @LengthPrefixed val value: String,
) : MqttProperty

// TODO(buffer-v1): AuthenticationData is bytes per MQTT v5 §3.1.2.11.10, not UTF-8.
//  Same deferral pattern as CorrelationData.
@PacketType(0x16)
@ProtocolMessage
data class AuthenticationData(
    val id: PropertyId = PropertyId(0x16u),
    @LengthPrefixed val value: String,
) : MqttProperty
