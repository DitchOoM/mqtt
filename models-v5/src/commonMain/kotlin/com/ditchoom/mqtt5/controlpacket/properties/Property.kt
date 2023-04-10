package com.ditchoom.mqtt5.controlpacket.properties

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.allocate
import com.ditchoom.mqtt.MalformedPacketException
import com.ditchoom.mqtt.ProtocolError
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.readMqttUtf8StringNotValidatedSized
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.readVariableByteInteger
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.writeMqttUtf8String
import com.ditchoom.mqtt.controlpacket.QualityOfService.AT_LEAST_ONCE
import com.ditchoom.mqtt.controlpacket.QualityOfService.AT_MOST_ONCE
import com.ditchoom.mqtt.controlpacket.Topic
import com.ditchoom.mqtt.controlpacket.utf8Length

@Suppress("UNUSED_PARAMETER")
abstract class Property(
    val identifierByte: Byte,
    val type: Type,
    val willProperties: Boolean = false
) {
    open fun write(buffer: WriteBuffer): Int = 0

    open fun size(): Int = 0

    internal fun write(buffer: WriteBuffer, boolean: Boolean): Int {
        buffer.writeByte(identifierByte)
        buffer.writeUByte((if (boolean) 1 else 0).toUByte())
        return 2
    }

    fun size(number: UInt) = 5
    fun write(bytePacketBuilder: WriteBuffer, number: UInt): Int {
        bytePacketBuilder.writeByte(identifierByte)
        bytePacketBuilder.writeUInt(number)
        return 5
    }

    fun size(number: ULong) = 9
    fun write(bytePacketBuilder: WriteBuffer, number: ULong): Int {
        bytePacketBuilder.writeByte(identifierByte)
        bytePacketBuilder.writeULong(number)
        return 9
    }

    fun size(number: UShort) = 3u
    fun write(bytePacketBuilder: WriteBuffer, number: UShort): Int {
        bytePacketBuilder.writeByte(identifierByte)
        bytePacketBuilder.writeUShort(number)
        return 3
    }

    fun size(string: String) = UShort.SIZE_BYTES + 1 + string.utf8Length()

    fun write(bytePacketBuilder: WriteBuffer, string: String): Int {
        val startPosition = bytePacketBuilder.position()
        bytePacketBuilder.writeByte(identifierByte)
        bytePacketBuilder.writeMqttUtf8String(string)
        return bytePacketBuilder.position() - startPosition
    }
}

fun Collection<Property?>.addTo(map: HashMap<Int, Any>) {
    forEach {
        map.addProperty(it)
    }
}

fun HashMap<Int, Any>.addProperty(property: Property?) {
    property ?: return
    put(property.identifierByte.toInt(), property)
}

fun ReadBuffer.readPlatformBuffer(): ReadBuffer {
    val size = readUnsignedShort().toInt()
    return if (size > 0) {
        readBytes(size)
    } else {
        PlatformBuffer.allocate(0)
    }
}

fun ReadBuffer.readMqttProperty(): Pair<Property, Int> {
    val property = when (val identifierByte = readByte().toInt()) {
        0x01 -> {
            PayloadFormatIndicator(readByte() == 1.toByte())
        }

        0x02 -> {
            MessageExpiryInterval(readUnsignedInt().toLong())
        }

        0x03 -> {
            ContentType(readMqttUtf8StringNotValidatedSized().second)
        }

        0x08 -> ResponseTopic(Topic.fromOrThrow(readMqttUtf8StringNotValidatedSized().second, Topic.Type.Name))
        0x09 -> CorrelationData(readPlatformBuffer())
        0x0B -> SubscriptionIdentifier(readVariableByteInteger().toLong())
        0x11 -> SessionExpiryInterval(readUnsignedLong())
        0x12 -> AssignedClientIdentifier(readMqttUtf8StringNotValidatedSized().second)
        0x13 -> ServerKeepAlive(readUnsignedShort().toInt())
        0x15 -> AuthenticationMethod(readMqttUtf8StringNotValidatedSized().second)
        0x16 -> AuthenticationData(readPlatformBuffer())
        0x17 -> {
            val uByteAsInt = readByte().toInt()
            if (!(uByteAsInt == 0 || uByteAsInt == 1)) {
                throw ProtocolError(
                    "Request Problem Information cannot have a value other than 0 or 1" +
                            "see: https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477353"
                )
            }
            RequestProblemInformation(uByteAsInt == 1)
        }

        0x18 -> WillDelayInterval(readUnsignedInt().toLong())
        0x19 -> {
            val uByteAsInt = readUnsignedByte().toInt()
            if (!(uByteAsInt == 0 || uByteAsInt == 1)) {
                throw ProtocolError(
                    "Request Response Information cannot have a value other than 0 or 1" +
                            "see: https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477352"
                )
            }
            RequestResponseInformation(uByteAsInt == 1)
        }

        0x1A -> ResponseInformation(readMqttUtf8StringNotValidatedSized().second)
        0x1C -> ServerReference(readMqttUtf8StringNotValidatedSized().second)
        0x1F -> ReasonString(readMqttUtf8StringNotValidatedSized().second)
        0x21 -> ReceiveMaximum(readUnsignedShort().toInt())
        0x22 -> TopicAlias(readUnsignedShort().toInt())
        0x23 -> TopicAliasMaximum(readUnsignedShort().toInt())
        0x24 -> MaximumQos(if (readByte() == 1.toByte()) AT_LEAST_ONCE else AT_MOST_ONCE) // Should not be present for 2
        0x25 -> RetainAvailable(readByte() == 1.toByte())
        0x26 -> UserProperty(
            readMqttUtf8StringNotValidatedSized().second,
            readMqttUtf8StringNotValidatedSized().second
        )

        0x27 -> MaximumPacketSize(readUnsignedLong())
        0x28 -> WildcardSubscriptionAvailable(readByte() == 1.toByte())
        0x29 -> SubscriptionIdentifierAvailable(readByte() == 1.toByte())
        0x2A -> SharedSubscriptionAvailable(readByte() == 1.toByte())
        else -> throw MalformedPacketException(
            "Invalid Byte Code while reading properties $identifierByte 0x${
                identifierByte.toString(
                    16
                )
            }"
        )
    }
    return Pair(property, property.size() + 1)
}

fun ReadBuffer.readProperties() = readPropertiesSized().second

fun ReadBuffer.readPropertiesSized(): Pair<Int, Collection<Property>?> {
    val propertyLength = readVariableByteInteger()
    val propertyBytes = if (propertyLength < 1) {
        PlatformBuffer.allocate(0)
    } else {
        readBytes(propertyLength)
    }
    val list = mutableListOf<Property>()
    while (propertyBytes.hasRemaining()) {
        val (property, _) = propertyBytes.readMqttProperty()
        list += property
    }
    return Pair(propertyLength, if (list.isEmpty()) null else list)
}
