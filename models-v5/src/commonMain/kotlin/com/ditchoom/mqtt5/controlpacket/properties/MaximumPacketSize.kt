package com.ditchoom.mqtt5.controlpacket.properties

import com.ditchoom.buffer.WriteBuffer

data class MaximumPacketSize(val packetSizeLimitationBytes: ULong) :
    Property(0x27, Type.FOUR_BYTE_INTEGER) {
    override fun size(): Int = size(packetSizeLimitationBytes)
    override fun write(buffer: WriteBuffer): Int =
        write(buffer, packetSizeLimitationBytes)
}
