package com.ditchoom.mqtt5.controlpacket.properties

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.EncodeContext

/**
 * Measures the encoded wire size of an [MqttProperty] by encoding it to a temporary buffer.
 * Includes the identifier byte + payload bytes.
 */
fun encodedSize(prop: MqttProperty): Int {
    val buf = BufferFactory.Default.allocate(512)
    MqttPropertyCodec.encode(buf, prop, EncodeContext.Empty)
    buf.resetForRead()
    return buf.remaining()
}

/** Encodes an [MqttProperty] to the given buffer. */
fun encodeProperty(
    buf: WriteBuffer,
    prop: MqttProperty,
) {
    MqttPropertyCodec.encode(buf, prop, EncodeContext.Empty)
}
