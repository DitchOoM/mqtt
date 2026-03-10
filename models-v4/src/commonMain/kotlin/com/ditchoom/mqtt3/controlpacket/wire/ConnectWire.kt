package com.ditchoom.mqtt3.controlpacket.wire

import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.Payload
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.WhenTrue
import kotlin.jvm.JvmInline

/**
 * Value class wrapping the MQTT v3.1.1 Connect Flags byte.
 *
 * Bit layout:
 * - Bit 7: Username Flag
 * - Bit 6: Password Flag
 * - Bit 5: Will Retain
 * - Bits 4-3: Will QoS
 * - Bit 2: Will Flag
 * - Bit 1: Clean Session
 * - Bit 0: Reserved (must be 0)
 */
@JvmInline
value class ConnectFlagsValue(val raw: UByte) {
    val reserved: Boolean get() = raw.toInt() and 1 == 1
    val cleanSession: Boolean get() = (raw.toInt() shr 1) and 1 == 1
    val willFlag: Boolean get() = (raw.toInt() shr 2) and 1 == 1
    val willQosBit1: Boolean get() = (raw.toInt() shr 3) and 1 == 1
    val willQosBit2: Boolean get() = (raw.toInt() shr 4) and 1 == 1
    val willQos: Int get() = (raw.toInt() shr 3) and 3
    val willRetain: Boolean get() = (raw.toInt() shr 5) and 1 == 1
    val passwordFlag: Boolean get() = (raw.toInt() shr 6) and 1 == 1
    val usernameFlag: Boolean get() = (raw.toInt() shr 7) and 1 == 1
}

/**
 * Wire model for MQTT v3.1.1 CONNECT packet (variable header + payload combined).
 *
 * The will payload is binary data (not necessarily UTF-8), modeled as a generic `@Payload` type.
 * All other optional fields (willTopic, username, password) are UTF-8 strings.
 */
@ProtocolMessage
data class ConnectWire<@Payload WP>(
    @LengthPrefixed val protocolName: String,
    val protocolLevel: UByte,
    val connectFlags: ConnectFlagsValue,
    val keepAlive: UShort,
    @LengthPrefixed val clientId: String,
    @WhenTrue("connectFlags.willFlag") @LengthPrefixed val willTopic: String? = null,
    @WhenTrue("connectFlags.willFlag") @LengthPrefixed val willPayload: WP? = null,
    @WhenTrue("connectFlags.usernameFlag") @LengthPrefixed val username: String? = null,
    @WhenTrue("connectFlags.passwordFlag") @LengthPrefixed val password: String? = null,
)
