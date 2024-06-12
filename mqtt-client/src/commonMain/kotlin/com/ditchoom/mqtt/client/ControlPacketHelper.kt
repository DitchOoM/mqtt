package com.ditchoom.mqtt.client

import com.ditchoom.buffer.AllocationZone
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.allocate
import com.ditchoom.mqtt.controlpacket.ControlPacket

fun ControlPacket.toBuffer(zone: AllocationZone = AllocationZone.Direct) = listOf(this).toBuffer(zone)

fun Collection<ControlPacket>.toBuffer(zone: AllocationZone = AllocationZone.Direct): PlatformBuffer {
    val packetSize =
        fold(0) { currentPacketSize, controlPacket ->
            currentPacketSize + controlPacket.packetSize()
        }
    return fold(PlatformBuffer.allocate(packetSize, zone)) { buffer, controlPacket ->
        controlPacket.serialize(buffer)
        buffer
    }
}
