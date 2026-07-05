package com.ditchoom.mqtt5.controlpacket.properties

import com.ditchoom.buffer.codec.OwnedBytesHandle
import com.ditchoom.buffer.codec.OwnedBytesHandleCodec
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
 * field (directional-codec processor requirement). Single-arg secondary constructors
 * default the id so callers can write `PayloadFormatIndicator(true)` instead of
 * `PayloadFormatIndicator(isUtf8 = true)`.
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
) : MqttProperty {
    constructor(isUtf8: Boolean) : this(PropertyId(0x01u), isUtf8)
}

@PacketType(0x17)
@ProtocolMessage
data class RequestProblemInformation(
    val id: PropertyId = PropertyId(0x17u),
    val enabled: Boolean,
) : MqttProperty {
    constructor(enabled: Boolean) : this(PropertyId(0x17u), enabled)
}

@PacketType(0x19)
@ProtocolMessage
data class RequestResponseInformation(
    val id: PropertyId = PropertyId(0x19u),
    val enabled: Boolean,
) : MqttProperty {
    constructor(enabled: Boolean) : this(PropertyId(0x19u), enabled)
}

@PacketType(0x24)
@ProtocolMessage
data class MaximumQos(
    val id: PropertyId = PropertyId(0x24u),
    val qos1Allowed: Boolean,
) : MqttProperty {
    constructor(qos1Allowed: Boolean) : this(PropertyId(0x24u), qos1Allowed)
}

@PacketType(0x25)
@ProtocolMessage
data class RetainAvailable(
    val id: PropertyId = PropertyId(0x25u),
    val supported: Boolean,
) : MqttProperty {
    constructor(supported: Boolean) : this(PropertyId(0x25u), supported)
}

@PacketType(0x28)
@ProtocolMessage
data class WildcardSubscriptionAvailable(
    val id: PropertyId = PropertyId(0x28u),
    val supported: Boolean,
) : MqttProperty {
    constructor(supported: Boolean) : this(PropertyId(0x28u), supported)
}

@PacketType(0x29)
@ProtocolMessage
data class SubscriptionIdentifierAvailable(
    val id: PropertyId = PropertyId(0x29u),
    val supported: Boolean,
) : MqttProperty {
    constructor(supported: Boolean) : this(PropertyId(0x29u), supported)
}

@PacketType(0x2A)
@ProtocolMessage
data class SharedSubscriptionAvailable(
    val id: PropertyId = PropertyId(0x2Au),
    val supported: Boolean,
) : MqttProperty {
    constructor(supported: Boolean) : this(PropertyId(0x2Au), supported)
}

// ── Two-byte integer properties (1-byte identifier + UShort) ────────────

@PacketType(0x21)
@ProtocolMessage
data class ReceiveMaximum(
    val id: PropertyId = PropertyId(0x21u),
    val max: UShort,
) : MqttProperty {
    constructor(max: UShort) : this(PropertyId(0x21u), max)
}

@PacketType(0x22)
@ProtocolMessage
data class TopicAlias(
    val id: PropertyId = PropertyId(0x22u),
    val value: UShort,
) : MqttProperty {
    constructor(value: UShort) : this(PropertyId(0x22u), value)
}

@PacketType(0x23)
@ProtocolMessage
data class TopicAliasMaximum(
    val id: PropertyId = PropertyId(0x23u),
    val max: UShort,
) : MqttProperty {
    constructor(max: UShort) : this(PropertyId(0x23u), max)
}

@PacketType(0x13)
@ProtocolMessage
data class ServerKeepAlive(
    val id: PropertyId = PropertyId(0x13u),
    val seconds: UShort,
) : MqttProperty {
    constructor(seconds: UShort) : this(PropertyId(0x13u), seconds)
}

// ── Four-byte integer properties (1-byte identifier + UInt) ─────────────

@PacketType(0x02)
@ProtocolMessage
data class MessageExpiryInterval(
    val id: PropertyId = PropertyId(0x02u),
    val seconds: UInt,
) : MqttProperty {
    constructor(seconds: UInt) : this(PropertyId(0x02u), seconds)
}

@PacketType(0x11)
@ProtocolMessage
data class SessionExpiryInterval(
    val id: PropertyId = PropertyId(0x11u),
    val seconds: UInt,
) : MqttProperty {
    constructor(seconds: UInt) : this(PropertyId(0x11u), seconds)
}

@PacketType(0x18)
@ProtocolMessage
data class WillDelayInterval(
    val id: PropertyId = PropertyId(0x18u),
    val seconds: UInt,
) : MqttProperty {
    constructor(seconds: UInt) : this(PropertyId(0x18u), seconds)
}

@PacketType(0x27)
@ProtocolMessage
data class MaximumPacketSize(
    val id: PropertyId = PropertyId(0x27u),
    val bytes: UInt,
) : MqttProperty {
    constructor(bytes: UInt) : this(PropertyId(0x27u), bytes)
}

// ── UTF-8 string properties (1-byte identifier + length-prefixed string) ─

@PacketType(0x03)
@ProtocolMessage
data class ContentType(
    val id: PropertyId = PropertyId(0x03u),
    @LengthPrefixed val value: String,
) : MqttProperty {
    constructor(value: String) : this(PropertyId(0x03u), value)
}

@PacketType(0x08)
@ProtocolMessage
data class ResponseTopic(
    val id: PropertyId = PropertyId(0x08u),
    @LengthPrefixed val value: String,
) : MqttProperty {
    constructor(value: String) : this(PropertyId(0x08u), value)
}

@PacketType(0x12)
@ProtocolMessage
data class AssignedClientIdentifier(
    val id: PropertyId = PropertyId(0x12u),
    @LengthPrefixed val value: String,
) : MqttProperty {
    constructor(value: String) : this(PropertyId(0x12u), value)
}

@PacketType(0x15)
@ProtocolMessage
data class AuthenticationMethod(
    val id: PropertyId = PropertyId(0x15u),
    @LengthPrefixed val value: String,
) : MqttProperty {
    constructor(value: String) : this(PropertyId(0x15u), value)
}

@PacketType(0x1A)
@ProtocolMessage
data class ResponseInformation(
    val id: PropertyId = PropertyId(0x1Au),
    @LengthPrefixed val value: String,
) : MqttProperty {
    constructor(value: String) : this(PropertyId(0x1Au), value)
}

@PacketType(0x1C)
@ProtocolMessage
data class ServerReference(
    val id: PropertyId = PropertyId(0x1Cu),
    @LengthPrefixed val value: String,
) : MqttProperty {
    constructor(value: String) : this(PropertyId(0x1Cu), value)
}

@PacketType(0x1F)
@ProtocolMessage
data class ReasonString(
    val id: PropertyId = PropertyId(0x1Fu),
    @LengthPrefixed val value: String,
) : MqttProperty {
    constructor(value: String) : this(PropertyId(0x1Fu), value)
}

// ── UTF-8 string pair (1-byte identifier + two length-prefixed strings) ──

@PacketType(0x26)
@ProtocolMessage
data class UserProperty(
    val id: PropertyId = PropertyId(0x26u),
    @LengthPrefixed val key: String,
    @LengthPrefixed val value: String,
) : MqttProperty {
    constructor(key: String, value: String) : this(PropertyId(0x26u), key, value)
}

// ── Variable byte integer property ──────────────────────────────────────

@PacketType(0x0B)
@ProtocolMessage
data class SubscriptionIdentifier(
    val id: PropertyId = PropertyId(0x0Bu),
    @UseCodec(VariableByteIntegerCodec::class) val value: UInt,
) : MqttProperty {
    // Int secondary for ergonomic test calls (`SubscriptionIdentifier(1)`). UInt and Int
    // erase to the same JVM signature, so we expose Int only — UInt callers use the
    // primary with named arg: `SubscriptionIdentifier(value = 1u)`.
    constructor(value: Int) : this(PropertyId(0x0Bu), value.toUInt())
}

// ── Binary data properties — OwnedBytesHandle wire carriers (bytes-exact per spec) ────

// MQTT v5 §3.3.2.3.6 — Correlation Data is binary, not UTF-8. The handle carries the
// length-prefixed bytes verbatim; consumers extract via `value.asReadBuffer()` (zero-copy)
// when the application chooses to interpret them.
@PacketType(0x09)
@ProtocolMessage
data class CorrelationData(
    val id: PropertyId = PropertyId(0x09u),
    @LengthPrefixed @UseCodec(OwnedBytesHandleCodec::class) val value: OwnedBytesHandle,
) : MqttProperty {
    constructor(value: OwnedBytesHandle) : this(PropertyId(0x09u), value)
}

// MQTT v5 §3.1.2.11.10 — Authentication Data is binary auth-method-specific material.
@PacketType(0x16)
@ProtocolMessage
data class AuthenticationData(
    val id: PropertyId = PropertyId(0x16u),
    @LengthPrefixed @UseCodec(OwnedBytesHandleCodec::class) val value: OwnedBytesHandle,
) : MqttProperty {
    constructor(value: OwnedBytesHandle) : this(PropertyId(0x16u), value)
}
