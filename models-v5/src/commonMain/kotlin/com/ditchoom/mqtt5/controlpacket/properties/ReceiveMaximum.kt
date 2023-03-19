package com.ditchoom.mqtt5.controlpacket.properties

import com.ditchoom.buffer.WriteBuffer

data class ReceiveMaximum(val maxQos1Or2ConcurrentMessages: Int) :
    Property(0x21, Type.TWO_BYTE_INTEGER) {
    override fun size(): Int = 3
    override fun write(buffer: WriteBuffer): Int =
        write(buffer, maxQos1Or2ConcurrentMessages.toUShort())
}
