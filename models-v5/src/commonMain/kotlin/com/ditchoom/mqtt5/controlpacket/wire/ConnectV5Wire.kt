package com.ditchoom.mqtt5.controlpacket.wire

import com.ditchoom.buffer.codec.annotations.LengthPrefixed
import com.ditchoom.buffer.codec.annotations.Payload
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.buffer.codec.annotations.WhenTrue
import com.ditchoom.mqtt.codec.annotations.MqttProperties
import com.ditchoom.mqtt5.controlpacket.properties.MqttProperty
import kotlin.jvm.JvmInline

@JvmInline
value class ConnectV5FlagsValue(val raw: UByte) {
    val reserved: Boolean get() = raw.toInt() and 1 == 1
    val cleanStart: Boolean get() = (raw.toInt() shr 1) and 1 == 1
    val willFlag: Boolean get() = (raw.toInt() shr 2) and 1 == 1
    val willQosBit1: Boolean get() = (raw.toInt() shr 3) and 1 == 1
    val willQosBit2: Boolean get() = (raw.toInt() shr 4) and 1 == 1
    val willQos: Int get() = (raw.toInt() shr 3) and 3
    val willRetain: Boolean get() = (raw.toInt() shr 5) and 1 == 1
    val passwordFlag: Boolean get() = (raw.toInt() shr 6) and 1 == 1
    val usernameFlag: Boolean get() = (raw.toInt() shr 7) and 1 == 1
}

@ProtocolMessage
data class ConnectV5Wire<@Payload WP>(
    @LengthPrefixed val protocolName: String,
    val protocolLevel: UByte,
    val connectFlags: ConnectV5FlagsValue,
    val keepAlive: UShort,
    @MqttProperties val properties: Collection<MqttProperty>?,
    @LengthPrefixed val clientId: String,
    @WhenTrue("connectFlags.willFlag") @MqttProperties val willProperties: Collection<MqttProperty>? = null,
    @WhenTrue("connectFlags.willFlag") @LengthPrefixed val willTopic: String? = null,
    @WhenTrue("connectFlags.willFlag") @LengthPrefixed val willPayload: WP? = null,
    @WhenTrue("connectFlags.usernameFlag") @LengthPrefixed val username: String? = null,
    @WhenTrue("connectFlags.passwordFlag") @LengthPrefixed val password: String? = null,
)
