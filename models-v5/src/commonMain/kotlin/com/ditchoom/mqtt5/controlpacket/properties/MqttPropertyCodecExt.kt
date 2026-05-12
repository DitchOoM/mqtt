package com.ditchoom.mqtt5.controlpacket.properties

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.utf8Length
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.readVariableByteInteger
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.variableByteSize
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.writeVariableByteInteger

// MQTT v5 property-section helpers — the irreducibly MQTT-specific code that wraps the
// generated MqttPropertyCodec dispatch with a Variable Byte Integer length prefix
// (MQTT 5.0 §2.2.2). VBI is a spec-named encoding and stays in MQTT-land rather than
// leaking into buffer-codec's generic LengthPrefix enum.
//
// Per-property encode/decode/size walks that used to live here are gone — the generated
// MqttPropertyCodec handles them via distinct <AD, CD> type parameters for the two
// @Payload variants (AuthenticationData / CorrelationData).

/**
 * Decodes a VBI-prefixed MQTT v5 property section into a list of typed properties.
 *
 * Callers supply the binary-data decoders for [CorrelationData] and [AuthenticationData];
 * everything else is handled by the generated codec.
 */
fun <AD, CD> ReadBuffer.decodeMqttProperties(
    decodeAuthenticationData: AuthenticationDataContext.(ReadBuffer) -> AD,
    decodeCorrelationData: CorrelationDataContext.(ReadBuffer) -> CD,
): List<MqttProperty> {
    val propertyLength = readVariableByteInteger()
    if (propertyLength < 1) return emptyList()
    val endPosition = position() + propertyLength
    val list = mutableListOf<MqttProperty>()
    while (position() < endPosition) {
        list += MqttPropertyCodec.decode<AD, CD>(this, decodeAuthenticationData, decodeCorrelationData)
    }
    return list
}

/**
 * Encodes a VBI-prefixed MQTT v5 property section.
 */
fun <AD, CD> WriteBuffer.encodeMqttProperties(
    properties: List<MqttProperty>,
    encodeAuthenticationData: (WriteBuffer, AD) -> Unit,
    encodeCorrelationData: (WriteBuffer, CD) -> Unit,
) {
    val bodySize = mqttPropertiesSize(properties)
    writeVariableByteInteger(bodySize)
    for (property in properties) {
        MqttPropertyCodec.encode<AD, CD>(this, property, encodeAuthenticationData, encodeCorrelationData)
    }
}

// ──────────────────────── Size walk (for the VBI prefix) ────────────────────────

/**
 * Computes the encoded byte size of a single MqttProperty (identifier byte + payload).
 *
 * Required because the VBI prefix on a property section must be written before the body,
 * so we need the body length without actually encoding to a scratch buffer. Hand-walking
 * the variants is tedious but cheap and zero-allocation.
 */
fun mqttPropertySize(property: MqttProperty): Int {
    val payloadSize =
        when (property) {
            // Boolean: 1 byte
            is PayloadFormatIndicator -> 1
            is RequestProblemInformation -> 1
            is RequestResponseInformation -> 1
            is MaximumQos -> 1
            is RetainAvailable -> 1
            is WildcardSubscriptionAvailable -> 1
            is SubscriptionIdentifierAvailable -> 1
            is SharedSubscriptionAvailable -> 1
            // UShort: 2 bytes
            is ReceiveMaximum -> 2
            is TopicAlias -> 2
            is TopicAliasMaximum -> 2
            is ServerKeepAlive -> 2
            // UInt: 4 bytes
            is MessageExpiryInterval -> 4
            is SessionExpiryInterval -> 4
            is WillDelayInterval -> 4
            is MaximumPacketSize -> 4
            // Length-prefixed strings: 2 (length prefix) + utf8 bytes
            is ContentType -> 2 + property.value.utf8Length()
            is ResponseTopic -> 2 + property.value.utf8Length()
            is AssignedClientIdentifier -> 2 + property.value.utf8Length()
            is AuthenticationMethod -> 2 + property.value.utf8Length()
            is ResponseInformation -> 2 + property.value.utf8Length()
            is ServerReference -> 2 + property.value.utf8Length()
            is ReasonString -> 2 + property.value.utf8Length()
            // String pair: 2+key + 2+value
            is UserProperty -> 2 + property.key.utf8Length() + 2 + property.value.utf8Length()
            // Variable byte integer
            is SubscriptionIdentifier -> variableByteSize(property.value.toInt()).toInt()
            // Binary data: 2 (length prefix) + payload bytes. Phase A intermediates the
            // payload via `value: String` — the utf8 byte count is the wire byte count.
            is CorrelationData -> 2 + property.value.utf8Length()
            is AuthenticationData -> 2 + property.value.utf8Length()
        }
    return 1 + payloadSize
}

fun mqttPropertiesSize(properties: List<MqttProperty>): Int {
    var size = 0
    for (property in properties) {
        size += mqttPropertySize(property)
    }
    return size
}

fun mqttPropertiesSectionSize(properties: List<MqttProperty>): Int {
    val bodySize = mqttPropertiesSize(properties)
    return bodySize + variableByteSize(bodySize)
}
