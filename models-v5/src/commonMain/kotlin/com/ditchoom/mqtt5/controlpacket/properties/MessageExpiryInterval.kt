package com.ditchoom.mqtt5.controlpacket.properties

import com.ditchoom.buffer.WriteBuffer

data class MessageExpiryInterval(val seconds: Long) :
    Property(0x02, Type.FOUR_BYTE_INTEGER, willProperties = true) {
    override fun size(): Int = size(seconds.toUInt())
    override fun write(buffer: WriteBuffer): Int = write(buffer, seconds.toUInt())
}
