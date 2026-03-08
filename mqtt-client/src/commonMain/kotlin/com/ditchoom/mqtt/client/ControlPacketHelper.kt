package com.ditchoom.mqtt.client

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.mqtt.controlpacket.ControlPacket

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
