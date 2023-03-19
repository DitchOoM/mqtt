package com.ditchoom.mqtt.client

import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.allocate
import com.ditchoom.mqtt.controlpacket.ControlPacket

fun ControlPacket.toBuffer() = listOf(this).toBuffer()

fun Collection<ControlPacket>.toBuffer(): PlatformBuffer {
    val packetSize = fold(0) { currentPacketSize, controlPacket ->
        currentPacketSize + controlPacket.packetSize()
    }
    return fold(PlatformBuffer.allocate(packetSize)) { buffer, controlPacket ->
        controlPacket.serialize(buffer)
        buffer
    }
}
