package com.ditchoom.mqtt5.controlpacket.properties

import com.ditchoom.buffer.WriteBuffer

data class ServerKeepAlive(val seconds: Int) : Property(0x13, Type.TWO_BYTE_INTEGER) {
    override fun size(): Int = 3

    override fun write(buffer: WriteBuffer): Int = write(buffer, seconds.toUShort())
}
