package com.ditchoom.mqtt5.controlpacket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.annotations.ProtocolMessage
import com.ditchoom.mqtt.MalformedPacketException
import com.ditchoom.mqtt.codec.annotations.MqttProperties
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.variableByteSize
import com.ditchoom.mqtt.controlpacket.format.ReasonCode
import com.ditchoom.mqtt.controlpacket.format.ReasonCode.SUCCESS
import com.ditchoom.mqtt5.controlpacket.properties.MqttProperty
import com.ditchoom.mqtt5.controlpacket.properties.PropertyExtractor
import com.ditchoom.mqtt5.controlpacket.properties.ReasonString
import com.ditchoom.mqtt5.controlpacket.properties.UserProperty
import com.ditchoom.mqtt5.controlpacket.properties.mqttPropertiesSize

@ProtocolMessage
data class AckV5Body(
    val packetId: UShort,
    val reasonCode: UByte,
    @MqttProperties val properties: Collection<MqttProperty>?,
)

/**
 * Shared variable header for PUBACK, PUBREC, PUBREL, PUBCOMP packets (MQTT 5.0).
 * These four packet types have identical wire format and only differ in valid reason codes.
 */
data class AckVariableHeader(
    val packetIdentifier: Int,
    val reasonCode: ReasonCode = SUCCESS,
    val properties: AckProperties = AckProperties(),
) {
    fun size(): Int {
        val canOmit =
            reasonCode == SUCCESS &&
                properties.userProperty.isEmpty() &&
                properties.reasonString == null
        var size = UShort.SIZE_BYTES
        if (!canOmit) {
            val propsSize = properties.size()
            size += UByte.SIZE_BYTES + variableByteSize(propsSize) + propsSize
        }
        return size
    }

    companion object {
        fun from(
            buffer: ReadBuffer,
            remainingLength: Int,
            validReasonCodes: Map<UByte, ReasonCode>,
            packetName: String,
        ): AckVariableHeader {
            // MQTT 5.0 §3.4.1 / §3.5.1 / §3.6.1 / §3.7.1: ACK-family packets
            // (PUBACK, PUBREC, PUBREL, PUBCOMP) carry at minimum a 2-byte
            // packet identifier. Remaining length 0 or 1 is structurally
            // malformed — the pre-fix code fell through to the full codec
            // decode which then silently returned garbage values instead of
            // rejecting. Reject at the top per spec.
            if (remainingLength < 2) {
                throw MalformedPacketException(
                    "$packetName remaining length $remainingLength is below the 2-byte packet identifier minimum",
                )
            }
            if (remainingLength == 2) {
                return AckVariableHeader(buffer.readUnsignedShort().toInt())
            }
            val wire =
                if (remainingLength == 3) {
                    AckV5Body(buffer.readUnsignedShort(), buffer.readUnsignedByte(), null)
                } else {
                    AckV5BodyCodec.decode(buffer)
                }
            val reasonCode =
                validReasonCodes[wire.reasonCode]
                    ?: throw MalformedPacketException(
                        "Invalid $packetName reason code ${wire.reasonCode}",
                    )
            val props = AckProperties.from(wire.properties, packetName)
            return AckVariableHeader(wire.packetId.toInt(), reasonCode, props)
        }
    }
}

/**
 * Shared properties for ACK packets — just ReasonString and UserProperty.
 */
data class AckProperties(
    val reasonString: String? = null,
    val userProperty: List<Pair<String, String>> = emptyList(),
) {
    val props: List<MqttProperty> =
        buildList {
            if (reasonString != null) {
                add(ReasonString(reasonString))
            }
            for ((key, value) in userProperty) {
                add(UserProperty(key, value))
            }
        }

    fun size(): Int = mqttPropertiesSize(props)

    companion object {
        fun from(
            keyValuePairs: Collection<MqttProperty>?,
            packetName: String,
        ): AckProperties {
            val p = PropertyExtractor(keyValuePairs, packetName)
            val reasonString = p.single<ReasonString>()?.value
            val userProperty = p.list<UserProperty>().map { it.key to it.value }
            p.rejectUnknown()
            return AckProperties(reasonString, userProperty)
        }
    }
}

/**
 * Shared encodeBody logic for ACK packets.
 */
fun encodeAckBody(
    writeBuffer: WriteBuffer,
    variable: AckVariableHeader,
) {
    val canOmit =
        variable.reasonCode == SUCCESS &&
            variable.properties.userProperty.isEmpty() &&
            variable.properties.reasonString == null
    if (canOmit) {
        writeBuffer.writeUShort(variable.packetIdentifier.toUShort())
    } else {
        AckV5BodyCodec.encode(
            writeBuffer,
            AckV5Body(
                variable.packetIdentifier.toUShort(),
                variable.reasonCode.byte,
                variable.properties.props,
            ),
        )
    }
}
