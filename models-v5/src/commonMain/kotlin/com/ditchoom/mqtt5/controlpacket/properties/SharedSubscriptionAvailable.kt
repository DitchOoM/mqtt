package com.ditchoom.mqtt5.controlpacket.properties

import com.ditchoom.buffer.WriteBuffer

data class SharedSubscriptionAvailable(val serverSupported: Boolean) : Property(0x2A, Type.BYTE) {
    override fun size(): Int = 2
    override fun write(buffer: WriteBuffer): Int = write(buffer, serverSupported)
}
