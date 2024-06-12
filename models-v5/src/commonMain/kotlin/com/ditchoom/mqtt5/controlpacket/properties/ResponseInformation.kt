package com.ditchoom.mqtt5.controlpacket.properties

import com.ditchoom.buffer.WriteBuffer

data class ResponseInformation(val requestResponseInformationInConnack: String) :
    Property(0x1A, Type.UTF_8_ENCODED_STRING) {
    override fun write(buffer: WriteBuffer): Int = write(buffer, requestResponseInformationInConnack)

    override fun size(): Int = size(requestResponseInformationInConnack)
}
