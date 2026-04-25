package com.ditchoom.mqtt5.controlpacket.properties

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer

/** ReadBuffer-based encoder for both binary-data property variants. */
private fun encodeBinaryData(
    buf: WriteBuffer,
    data: ReadBuffer,
) {
    data.position(0)
    buf.write(data)
}

/**
 * Measures the encoded wire size of an [MqttProperty] by encoding it to a temporary buffer.
 * Includes the identifier byte + payload bytes.
 */
fun encodedSize(prop: MqttProperty): Int {
    val buf = BufferFactory.Default.allocate(512)
    MqttPropertyCodec.encode<ReadBuffer, ReadBuffer>(
        buf,
        prop,
        encodeAuthenticationDataData = ::encodeBinaryData,
        encodeCorrelationDataData = ::encodeBinaryData,
    )
    buf.resetForRead()
    return buf.remaining()
}

/** Encodes an [MqttProperty] to the given buffer, handling binary data properties. */
fun encodeProperty(
    buf: WriteBuffer,
    prop: MqttProperty,
) {
    MqttPropertyCodec.encode<ReadBuffer, ReadBuffer>(
        buf,
        prop,
        encodeAuthenticationDataData = ::encodeBinaryData,
        encodeCorrelationDataData = ::encodeBinaryData,
    )
}
