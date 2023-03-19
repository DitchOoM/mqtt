package com.ditchoom.mqtt5.controlpacket.properties

import com.ditchoom.buffer.WriteBuffer

data class SubscriptionIdentifierAvailable(val serverSupported: Boolean) :
    Property(0x29, Type.BYTE) {
    override fun size(): Int = 2
    override fun write(buffer: WriteBuffer): Int = write(buffer, serverSupported)
}
