package com.ditchoom.mqtt5.controlpacket.properties

import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.mqtt.controlpacket.Topic

data class ResponseTopic(val value: Topic) :
    Property(0x08, Type.UTF_8_ENCODED_STRING, willProperties = true) {
    override fun write(buffer: WriteBuffer): Int = write(buffer, value.toString())
    override fun size(): Int = size(value.toString())
}
