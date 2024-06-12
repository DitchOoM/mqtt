package com.ditchoom.mqtt5.controlpacket.properties

import com.ditchoom.buffer.WriteBuffer

data class PayloadFormatIndicator(val willMessageIsUtf8: Boolean) : Property(
    0x01,
    Type.BYTE,
    willProperties = true,
) {
    override fun size(): Int = 2

    override fun write(buffer: WriteBuffer): Int = write(buffer, willMessageIsUtf8)
}
