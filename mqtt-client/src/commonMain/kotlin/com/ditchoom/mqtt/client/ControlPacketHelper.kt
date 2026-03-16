package com.ditchoom.mqtt.client

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.ReadWriteBuffer
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.IPublishMessage
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.encoding.variableByteSize
import com.ditchoom.mqtt.controlpacket.encoding.writeLengthPrefixedUtf8String
import com.ditchoom.mqtt.controlpacket.encoding.writeVariableByteInteger
import com.ditchoom.mqtt5.controlpacket.PublishMessage as PublishMessageV5

fun ControlPacket.toBuffer(factory: BufferFactory = BufferFactory.Default) = listOf(this).toBuffer(factory)

fun Collection<ControlPacket>.toBuffer(factory: BufferFactory = BufferFactory.Default): PlatformBuffer {
    val packetSize =
        fold(0) { currentPacketSize, controlPacket ->
            currentPacketSize + controlPacket.packetSize()
        }
    return fold(factory.allocate(packetSize)) { buffer, controlPacket ->
        controlPacket.serialize(buffer)
        buffer
    }
}

/**
 * Serializes only the PUBLISH fixed header + variable header (topic + packet ID) into [buffer],
 * excluding the payload. The fixed header's remaining length includes [payloadSize] so the
 * receiver sees a valid total length across the header and separate payload buffers.
 *
 * Uses backpatch: reserves space for fixed header, writes variable header in one pass,
 * then backpatches byte1 + VBI at the correct offset. Returns a zero-copy slice.
 */
fun IPublishMessage.serializeHeaderToSlice(
    buffer: ReadWriteBuffer,
    payloadSize: Int,
): ReadBuffer {
    val reserveStart = buffer.position()
    buffer.position(reserveStart + ControlPacket.MAX_FIXED_HEADER_SIZE)

    // Variable header: topic (length-prefixed UTF-8) + packet ID (if QoS > 0)
    buffer.writeLengthPrefixedUtf8String(topic.toString())
    if (qualityOfService != QualityOfService.AT_MOST_ONCE) {
        buffer.writeUShort(packetIdentifier.toUShort())
    }
    // MQTT v5 PUBLISH requires a properties section after the packet ID
    if (this is PublishMessageV5) {
        this.variable.properties.serialize(buffer)
    }

    val headerBodySize = buffer.position() - reserveStart - ControlPacket.MAX_FIXED_HEADER_SIZE
    val totalBodySize = headerBodySize + payloadSize
    val vbiSize = variableByteSize(totalBodySize).toInt()
    val actualStart = reserveStart + ControlPacket.MAX_FIXED_HEADER_SIZE - 1 - vbiSize

    // Backpatch fixed header: byte1 + VBI
    buffer[actualStart] = (this as ControlPacket).byte1.toByte()
    val savedPos = buffer.position()
    buffer.position(actualStart + 1)
    buffer.writeVariableByteInteger(totalBodySize)
    buffer.position(savedPos)

    // Zero-copy slice over the header region
    val savedLimit = buffer.limit()
    buffer.position(actualStart)
    buffer.setLimit(savedPos)
    val result = buffer.slice()
    buffer.position(savedPos)
    buffer.setLimit(savedLimit)
    return result
}
