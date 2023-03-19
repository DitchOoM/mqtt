package com.ditchoom.mqtt5.controlpacket.properties

import com.ditchoom.buffer.WriteBuffer

data class AuthenticationMethod(val value: String) :
    Property(0x15, Type.UTF_8_ENCODED_STRING) {
    override fun write(buffer: WriteBuffer): Int = write(buffer, value)
    override fun size(): Int = size(value)
}
