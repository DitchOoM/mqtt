package com.ditchoom.mqtt5.controlpacket.properties

import com.ditchoom.buffer.WriteBuffer

data class SessionExpiryInterval(val seconds: ULong) : Property(0x11, Type.FOUR_BYTE_INTEGER) {
    constructor(seconds: Int) : this(seconds.toULong())

    override fun size(): Int = size(seconds)
    override fun write(buffer: WriteBuffer): Int = write(buffer, seconds)
}
