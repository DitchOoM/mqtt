package com.ditchoom.mqtt5.controlpacket.properties

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.payload.PayloadReader
import com.ditchoom.buffer.utf8Length
import com.ditchoom.mqtt.MalformedPacketException
import com.ditchoom.mqtt.ProtocolError
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.readVariableByteInteger
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.variableByteSize
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.writeVariableByteInteger

/**
 * Validates that a byte value is 0 or 1 per MQTT 5.0 spec.
 * Boolean properties MUST have a value of 0 or 1; any other value is a Protocol Error.
 */
private fun ReadBuffer.readValidatedBooleanProperty(propertyName: String): Boolean {
    val raw = readByte().toInt() and 0xFF
    if (raw > 1) {
        throw ProtocolError("$propertyName must be 0 or 1, got $raw")
    }
    return raw == 1
}

/**
 * Consumer-provided callbacks for decoding binary property payloads.
 * Each binary property type gets its own callback so the consumer can
 * handle correlation data and authentication data differently.
 */
data class BinaryPropertyDecoders<CD, AD>(
    val decodeCorrelationData: CorrelationDataContext.(PayloadReader) -> CD,
    val decodeAuthenticationData: AuthenticationDataContext.(PayloadReader) -> AD,
)

/**
 * Decodes a single MQTT v5 property from the buffer.
 *
 * Reads the 1-byte property identifier, then dispatches to the appropriate
 * generated sub-codec. Binary data properties use consumer-provided callbacks
 * to control the memory representation.
 */
fun <CD, AD> ReadBuffer.decodeMqttProperty(decoders: BinaryPropertyDecoders<CD, AD>): MqttProperty {
    val id = readByte().toInt() and 0xFF
    return when (id) {
        // Boolean properties — validated per MQTT 5.0 spec (value must be 0 or 1)
        0x01 -> PayloadFormatIndicator(readValidatedBooleanProperty("Payload Format Indicator"))
        0x17 -> RequestProblemInformation(readValidatedBooleanProperty("Request Problem Information"))
        0x19 -> RequestResponseInformation(readValidatedBooleanProperty("Request Response Information"))
        0x24 -> MaximumQos(readValidatedBooleanProperty("Maximum QoS"))
        0x25 -> RetainAvailable(readValidatedBooleanProperty("Retain Available"))
        0x28 -> WildcardSubscriptionAvailable(readValidatedBooleanProperty("Wildcard Subscription Available"))
        0x29 -> SubscriptionIdentifierAvailable(readValidatedBooleanProperty("Subscription Identifier Available"))
        0x2A -> SharedSubscriptionAvailable(readValidatedBooleanProperty("Shared Subscription Available"))
        // Two-byte integer properties
        0x21 -> ReceiveMaximumCodec.decode(this)
        0x22 -> TopicAliasCodec.decode(this)
        0x23 -> TopicAliasMaximumCodec.decode(this)
        0x13 -> ServerKeepAliveCodec.decode(this)
        // Four-byte integer properties
        0x02 -> MessageExpiryIntervalCodec.decode(this)
        0x11 -> SessionExpiryIntervalCodec.decode(this)
        0x18 -> WillDelayIntervalCodec.decode(this)
        0x27 -> MaximumPacketSizeCodec.decode(this)
        // UTF-8 string properties
        0x03 -> ContentTypeCodec.decode(this)
        0x08 -> ResponseTopicCodec.decode(this)
        0x12 -> AssignedClientIdentifierCodec.decode(this)
        0x15 -> AuthenticationMethodCodec.decode(this)
        0x1A -> ResponseInformationCodec.decode(this)
        0x1C -> ServerReferenceCodec.decode(this)
        0x1F -> ReasonStringCodec.decode(this)
        // UTF-8 string pair
        0x26 -> UserPropertyCodec.decode(this)
        // Variable byte integer
        0x0B -> SubscriptionIdentifierCodec.decode(this)
        // Binary data (consumer-controlled)
        0x09 -> CorrelationDataCodec.decode(this, decoders.decodeCorrelationData)
        0x16 -> AuthenticationDataCodec.decode(this, decoders.decodeAuthenticationData)
        else -> throw MalformedPacketException(
            "Invalid property identifier: 0x${id.toString(16)}",
        )
    }
}

/**
 * Encodes a single MQTT v5 property to the buffer, including the identifier byte.
 */
fun <CD, AD> WriteBuffer.encodeMqttProperty(
    property: MqttProperty,
    encodeCorrelationData: (WriteBuffer, CD) -> Unit,
    encodeAuthenticationData: (WriteBuffer, AD) -> Unit,
) {
    // The generated MqttPropertyCodec.encode() handles writing the identifier byte
    // and delegating to the sub-codec. For @Payload variants we need to use
    // the sub-codecs directly with encode lambdas.
    when (property) {
        is CorrelationData<*> -> {
            writeByte(0x09.toByte())
            @Suppress("UNCHECKED_CAST")
            CorrelationDataCodec.encode(
                this,
                property as CorrelationData<CD>,
                encodeCorrelationData,
            )
        }
        is AuthenticationData<*> -> {
            writeByte(0x16.toByte())
            @Suppress("UNCHECKED_CAST")
            AuthenticationDataCodec.encode(
                this,
                property as AuthenticationData<AD>,
                encodeAuthenticationData,
            )
        }
        // All non-payload properties can use the generated dispatch codec
        else -> MqttPropertyCodec.encode(this, property)
    }
}

/**
 * Decodes a VBI-prefixed MQTT v5 property section into a list of typed properties.
 */
fun <CD, AD> ReadBuffer.decodeMqttProperties(decoders: BinaryPropertyDecoders<CD, AD>): List<MqttProperty> {
    val propertyLength = readVariableByteInteger()
    if (propertyLength < 1) return emptyList()
    val endPosition = position() + propertyLength
    val list = mutableListOf<MqttProperty>()
    while (position() < endPosition) {
        list += decodeMqttProperty(decoders)
    }
    return list
}

/**
 * Encodes a VBI-prefixed MQTT v5 property section.
 */
fun <CD, AD> WriteBuffer.encodeMqttProperties(
    properties: List<MqttProperty>,
    encodeCorrelationData: (WriteBuffer, CD) -> Unit,
    encodeAuthenticationData: (WriteBuffer, AD) -> Unit,
) {
    val bodySize = mqttPropertiesSize(properties)
    writeVariableByteInteger(bodySize)
    for (property in properties) {
        encodeMqttProperty(property, encodeCorrelationData, encodeAuthenticationData)
    }
}

/**
 * Computes the encoded byte size of a single MqttProperty (identifier byte + payload).
 */
fun mqttPropertySize(property: MqttProperty): Int {
    // 1 byte for the identifier + payload size
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
            is SubscriptionIdentifier -> variableByteSize(property.value).toInt()
            // Binary data: 2 (length prefix) + data length
            is CorrelationData<*> -> 2 + property.length.toInt()
            is AuthenticationData<*> -> 2 + property.length.toInt()
        }
    return 1 + payloadSize
}

/**
 * Computes the byte size of a list of MQTT v5 properties (excluding the VBI length prefix).
 */
fun mqttPropertiesSize(properties: List<MqttProperty>): Int {
    var size = 0
    for (property in properties) {
        size += mqttPropertySize(property)
    }
    return size
}

/**
 * Computes the total byte size of a VBI-prefixed MQTT v5 property section.
 */
fun mqttPropertiesSectionSize(properties: List<MqttProperty>): Int {
    val bodySize = mqttPropertiesSize(properties)
    return bodySize + variableByteSize(bodySize)
}

// ── @MqttProperties SPI-compatible functions ────────────────────────────
// These are called by the generated wire codecs via the @MqttProperties SPI.
// They default binary data payloads to ReadBuffer (zero-copy from the wire).

/** Default binary decoders: copy payload bytes into a ReadBuffer. */
private val defaultDecoders =
    BinaryPropertyDecoders<ReadBuffer, ReadBuffer>(
        decodeCorrelationData = { reader -> reader.copyToBuffer() },
        decodeAuthenticationData = { reader -> reader.copyToBuffer() },
    )

/**
 * Decodes a VBI-prefixed MQTT v5 property section.
 * Called by @MqttProperties SPI-generated wire codecs.
 */
fun ReadBuffer.readProperties(): Collection<MqttProperty>? {
    val result = decodeMqttProperties(defaultDecoders)
    return result.ifEmpty { null }
}

/**
 * Encodes a VBI-prefixed MQTT v5 property section.
 * Called by @MqttProperties SPI-generated wire codecs.
 */
fun WriteBuffer.writeProperties(properties: Collection<MqttProperty>?) {
    if (properties == null || properties.isEmpty()) {
        writeVariableByteInteger(0)
        return
    }
    val list = if (properties is List) properties else properties.toList()
    encodeMqttProperties<ReadBuffer, ReadBuffer>(
        list,
        encodeCorrelationData = { buf, data ->
            data.position(0)
            buf.write(data)
        },
        encodeAuthenticationData = { buf, data ->
            data.position(0)
            buf.write(data)
        },
    )
}

/**
 * Computes byte size of a property section (VBI length prefix + property bodies).
 * Called by @MqttProperties SPI-generated wire codecs.
 */
fun propertiesSize(properties: Collection<MqttProperty>?): Int {
    if (properties == null || properties.isEmpty()) return 1 // VBI for 0
    val list = if (properties is List) properties else properties.toList()
    val bodySize = mqttPropertiesSize(list)
    return bodySize + variableByteSize(bodySize)
}
