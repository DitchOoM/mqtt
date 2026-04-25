package com.ditchoom.mqtt5.controlpacket.properties

import com.ditchoom.buffer.codec.annotations.DispatchOn
import com.ditchoom.buffer.codec.annotations.DispatchValue
import com.ditchoom.buffer.codec.annotations.LengthFrom
import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.PacketType
import com.ditchoom.buffer.codec.annotations.Payload
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.mqtt.codec.annotations.MqttVariableByteInteger
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
 * Each property type is dispatched by its identifier byte. Simple properties use
 * value classes for zero-allocation overhead. Binary data properties use @Payload
 * so the consumer controls the memory representation.
 *
 * Wire format per property: 1-byte identifier + type-specific payload.
 */
@DispatchOn(PropertyId::class)
@ProtocolMessage
sealed interface MqttProperty

// ── Boolean properties (1-byte identifier + 1-byte value) ───────────────

@PacketType(value = 0x01, wire = 0x01)
@ProtocolMessage
@JvmInline
value class PayloadFormatIndicator(
    val isUtf8: Boolean,
) : MqttProperty

@PacketType(value = 0x17, wire = 0x17)
@ProtocolMessage
@JvmInline
value class RequestProblemInformation(
    val enabled: Boolean,
) : MqttProperty

@PacketType(value = 0x19, wire = 0x19)
@ProtocolMessage
@JvmInline
value class RequestResponseInformation(
    val enabled: Boolean,
) : MqttProperty

@PacketType(value = 0x24, wire = 0x24)
@ProtocolMessage
@JvmInline
value class MaximumQos(
    val qos1Allowed: Boolean,
) : MqttProperty

@PacketType(value = 0x25, wire = 0x25)
@ProtocolMessage
@JvmInline
value class RetainAvailable(
    val supported: Boolean,
) : MqttProperty

@PacketType(value = 0x28, wire = 0x28)
@ProtocolMessage
@JvmInline
value class WildcardSubscriptionAvailable(
    val supported: Boolean,
) : MqttProperty

@PacketType(value = 0x29, wire = 0x29)
@ProtocolMessage
@JvmInline
value class SubscriptionIdentifierAvailable(
    val supported: Boolean,
) : MqttProperty

@PacketType(value = 0x2A, wire = 0x2A)
@ProtocolMessage
@JvmInline
value class SharedSubscriptionAvailable(
    val supported: Boolean,
) : MqttProperty

// ── Two-byte integer properties (1-byte identifier + UShort) ────────────

@PacketType(value = 0x21, wire = 0x21)
@ProtocolMessage
@JvmInline
value class ReceiveMaximum(
    val max: UShort,
) : MqttProperty

@PacketType(value = 0x22, wire = 0x22)
@ProtocolMessage
@JvmInline
value class TopicAlias(
    val value: UShort,
) : MqttProperty

@PacketType(value = 0x23, wire = 0x23)
@ProtocolMessage
@JvmInline
value class TopicAliasMaximum(
    val max: UShort,
) : MqttProperty

@PacketType(value = 0x13, wire = 0x13)
@ProtocolMessage
@JvmInline
value class ServerKeepAlive(
    val seconds: UShort,
) : MqttProperty

// ── Four-byte integer properties (1-byte identifier + UInt) ─────────────

@PacketType(value = 0x02, wire = 0x02)
@ProtocolMessage
@JvmInline
value class MessageExpiryInterval(
    val seconds: UInt,
) : MqttProperty

@PacketType(value = 0x11, wire = 0x11)
@ProtocolMessage
@JvmInline
value class SessionExpiryInterval(
    val seconds: UInt,
) : MqttProperty

@PacketType(value = 0x18, wire = 0x18)
@ProtocolMessage
@JvmInline
value class WillDelayInterval(
    val seconds: UInt,
) : MqttProperty

@PacketType(value = 0x27, wire = 0x27)
@ProtocolMessage
@JvmInline
value class MaximumPacketSize(
    val bytes: UInt,
) : MqttProperty

// ── UTF-8 string properties (1-byte identifier + length-prefixed string) ─

@PacketType(value = 0x03, wire = 0x03)
@ProtocolMessage
@JvmInline
value class ContentType(
    @LengthPrefixed val value: String,
) : MqttProperty

@PacketType(value = 0x08, wire = 0x08)
@ProtocolMessage
@JvmInline
value class ResponseTopic(
    @LengthPrefixed val value: String,
) : MqttProperty

@PacketType(value = 0x12, wire = 0x12)
@ProtocolMessage
@JvmInline
value class AssignedClientIdentifier(
    @LengthPrefixed val value: String,
) : MqttProperty

@PacketType(value = 0x15, wire = 0x15)
@ProtocolMessage
@JvmInline
value class AuthenticationMethod(
    @LengthPrefixed val value: String,
) : MqttProperty

@PacketType(value = 0x1A, wire = 0x1A)
@ProtocolMessage
@JvmInline
value class ResponseInformation(
    @LengthPrefixed val value: String,
) : MqttProperty

@PacketType(value = 0x1C, wire = 0x1C)
@ProtocolMessage
@JvmInline
value class ServerReference(
    @LengthPrefixed val value: String,
) : MqttProperty

@PacketType(value = 0x1F, wire = 0x1F)
@ProtocolMessage
@JvmInline
value class ReasonString(
    @LengthPrefixed val value: String,
) : MqttProperty

// ── UTF-8 string pair (1-byte identifier + two length-prefixed strings) ──

@PacketType(value = 0x26, wire = 0x26)
@ProtocolMessage
data class UserProperty(
    @LengthPrefixed val key: String,
    @LengthPrefixed val value: String,
) : MqttProperty

// ── Variable byte integer property ──────────────────────────────────────

@PacketType(value = 0x0B, wire = 0x0B)
@ProtocolMessage
@JvmInline
value class SubscriptionIdentifier(
    @MqttVariableByteInteger val value: Int,
) : MqttProperty

// ── Binary data properties (consumer-defined type via @Payload) ─────────

@PacketType(value = 0x09, wire = 0x09)
@ProtocolMessage
data class CorrelationData<@Payload CD>(
    val length: UShort,
    @LengthFrom("length") val data: CD,
) : MqttProperty

@PacketType(value = 0x16, wire = 0x16)
@ProtocolMessage
data class AuthenticationData<@Payload AD>(
    val length: UShort,
    @LengthFrom("length") val data: AD,
) : MqttProperty
