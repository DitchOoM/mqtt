package com.ditchoom.mqtt5.controlpacket.properties

import com.ditchoom.buffer.WriteBuffer

data class ServerReference(val otherServer: String) :
    Property(0x1C, Type.UTF_8_ENCODED_STRING) {
    override fun write(buffer: WriteBuffer): Int = write(buffer, otherServer)
    override fun size(): Int = size(otherServer)
}
