package com.ditchoom.mqtt5.controlpacket.properties

import com.ditchoom.buffer.WriteBuffer

data class RequestResponseInformation(val requestServerToReturnInfoInConnack: Boolean) :
    Property(0x19, Type.BYTE) {
    override fun size(): Int = 2
    override fun write(buffer: WriteBuffer): Int = write(buffer, requestServerToReturnInfoInConnack)
}
