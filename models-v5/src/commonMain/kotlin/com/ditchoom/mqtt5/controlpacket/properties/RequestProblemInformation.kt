package com.ditchoom.mqtt5.controlpacket.properties

import com.ditchoom.buffer.WriteBuffer

data class RequestProblemInformation(val reasonStringOrUserPropertiesAreSentInFailures: Boolean) :
    Property(0x17, Type.BYTE) {
    override fun size(): Int = 2

    override fun write(buffer: WriteBuffer): Int = write(buffer, reasonStringOrUserPropertiesAreSentInFailures)
}
