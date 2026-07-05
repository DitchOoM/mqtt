package com.ditchoom.mqtt.client

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.mqtt.controlpacket.ControlPacket

fun ControlPacket.toBuffer(factory: BufferFactory = BufferFactory.Default) = listOf(this).toBuffer(factory)

fun Collection<ControlPacket>.toBuffer(factory: BufferFactory = BufferFactory.Default): ReadBuffer {
    if (size == 1) {
        // Single packet — use serialize(factory) which handles backpatch for typed payloads
        return first().serialize(factory)
    }
    // Batch path — all packets must have known sizes (reconnect queue, multi-packet sends)
    val packetSize =
        fold(0) { currentPacketSize, controlPacket ->
            currentPacketSize + controlPacket.packetSize()
        }
    val buffer = factory.allocate(packetSize)
    for (controlPacket in this) {
        controlPacket.serialize(buffer)
    }
    buffer.resetForRead()
    return buffer
}
