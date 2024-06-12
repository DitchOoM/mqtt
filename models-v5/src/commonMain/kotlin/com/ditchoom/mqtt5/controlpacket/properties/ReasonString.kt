package com.ditchoom.mqtt5.controlpacket.properties

import com.ditchoom.buffer.WriteBuffer

data class ReasonString(val diagnosticInfoDontParse: String) :
    Property(0x1F, Type.UTF_8_ENCODED_STRING) {
    override fun write(buffer: WriteBuffer): Int = write(buffer, diagnosticInfoDontParse)

    override fun size(): Int = size(diagnosticInfoDontParse)
}
