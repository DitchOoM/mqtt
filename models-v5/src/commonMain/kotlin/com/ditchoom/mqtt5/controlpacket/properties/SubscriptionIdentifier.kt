package com.ditchoom.mqtt5.controlpacket.properties

import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.variableByteSize
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.writeVariableByteInteger

data class SubscriptionIdentifier(val value: Long) : Property(0x0B, Type.VARIABLE_BYTE_INTEGER) {
    override fun size(): Int = variableByteSize(value.toInt()) + 1

    override fun write(buffer: WriteBuffer): Int {
        buffer.writeByte(identifierByte)
        buffer.writeVariableByteInteger(value.toInt())
        return size()
    }
}
