package com.ditchoom.mqtt5.controlpacket.properties

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer

data class CorrelationData(val data: ReadBuffer) :
    Property(0x09, Type.BINARY_DATA, willProperties = true) {
    override fun size(): Int {
        data.position(0)
        return 1 + UShort.SIZE_BYTES + data.remaining()
    }

    override fun write(buffer: WriteBuffer): Int {
        buffer.writeByte(identifierByte)
        data.position(0)
        buffer.writeUShort(data.remaining().toUShort())
        buffer.write(data)
        return size()
    }
}
