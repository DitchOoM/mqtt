package com.ditchoom.mqtt5.controlpacket.properties

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer

/**
 * Measures the encoded wire size of an [MqttProperty] by encoding it to a temporary buffer.
 * This includes the identifier byte + the payload bytes.
 *
 * Uses [encodeMqttProperty] which handles binary data properties (CorrelationData, AuthenticationData)
 * via ReadBuffer-based encode callbacks. [MqttPropertyCodec.encode] cannot handle those because
 * it requires EncodeContext with registered encode keys.
 */
fun encodedSize(prop: MqttProperty): Int {
    val buf = BufferFactory.Default.allocate(512)
    buf.encodeMqttProperty<ReadBuffer, ReadBuffer>(
        prop,
        encodeCorrelationData = { wb, data ->
            data.position(0)
            wb.write(data)
        },
        encodeAuthenticationData = { wb, data ->
            data.position(0)
            wb.write(data)
        },
    )
    buf.resetForRead()
    return buf.remaining()
}

/**
 * Encodes an [MqttProperty] to the given buffer, handling binary data properties.
 */
fun encodeProperty(
    buf: com.ditchoom.buffer.WriteBuffer,
    prop: MqttProperty,
) {
    buf.encodeMqttProperty<ReadBuffer, ReadBuffer>(
        prop,
        encodeCorrelationData = { wb, data ->
            data.position(0)
            wb.write(data)
        },
        encodeAuthenticationData = { wb, data ->
            data.position(0)
            wb.write(data)
        },
    )
}
