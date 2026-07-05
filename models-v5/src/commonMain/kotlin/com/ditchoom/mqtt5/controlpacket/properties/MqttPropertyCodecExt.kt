package com.ditchoom.mqtt5.controlpacket.properties

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.codec.OwnedBytesHandle
import com.ditchoom.buffer.codec.byteSize
import com.ditchoom.buffer.codec.ownedBytesFrom
import com.ditchoom.buffer.utf8Length
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.readVariableByteInteger
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.variableByteSize
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.writeVariableByteInteger

// MQTT v5 property-section helpers — the irreducibly MQTT-specific code that wraps the
// generated MqttPropertyCodec dispatch with a Variable Byte Integer length prefix
// (MQTT 5.0 §2.2.2). VBI is a spec-named encoding and stays in MQTT-land rather than
// leaking into buffer-codec's generic LengthPrefix enum.

/**
 * Decodes a VBI-prefixed MQTT v5 property section into a list of typed properties.
 */
fun ReadBuffer.decodeMqttProperties(): List<MqttProperty> {
    val propertyLength = readVariableByteInteger()
    if (propertyLength < 1) return emptyList()
    val endPosition = position() + propertyLength
    val list = mutableListOf<MqttProperty>()
    while (position() < endPosition) {
        list += MqttPropertyCodec.decode(this, DecodeContext.Empty)
    }
    return list
}

/**
 * Encodes a VBI-prefixed MQTT v5 property section.
 */
fun WriteBuffer.encodeMqttProperties(properties: List<MqttProperty>) {
    val bodySize = mqttPropertiesSize(properties)
    writeVariableByteInteger(bodySize)
    for (property in properties) {
        MqttPropertyCodec.encode(this, property, EncodeContext.Empty)
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
            // Binary data: 2 (length prefix) + payload bytes.
            is CorrelationData -> 2 + property.value.byteSize()
            is AuthenticationData -> 2 + property.value.byteSize()
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

/**
 * Copies the source buffer's remaining bytes into a consumer-owned [PlatformBuffer]
 * (Pattern #2 from the buffer-codec lockdown) and wraps it in an [OwnedBytesHandle].
 * Used at the typed-API → wire-property boundary for CorrelationData / AuthenticationData.
 * Reads a slice so the caller's read position is untouched.
 */
internal fun readBufferToOwnedBytes(source: ReadBuffer): OwnedBytesHandle {
    val slice = source.slice()
    val dst = BufferFactory.Default.allocate(slice.remaining())
    dst.write(slice)
    dst.resetForRead()
    return ownedBytesFrom(dst)
}
