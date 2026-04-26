package com.ditchoom.mqtt5.controlpacket.properties

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer

/**
 * Test-only conveniences for round-tripping the MQTT v5 property section without
 * constructing a full PUBLISH/CONNECT/etc. packet. Production code goes through
 * the generated codec dispatcher (with an EncodeContext/DecodeContext supplied by
 * the surrounding control packet).
 *
 * Binary-data variants (CorrelationData / AuthenticationData) take the `ReadBuffer`
 * slice handed to them by the codec — identity passthrough; no allocation. The slice
 * remains readable for as long as the source buffer is alive.
 */
fun ReadBuffer.readProperties(): Collection<MqttProperty>? {
    val result =
        decodeMqttProperties<ReadBuffer, ReadBuffer>(
            decodeAuthenticationData = { slice -> slice },
            decodeCorrelationData = { slice -> slice },
        )
    return result.ifEmpty { null }
}

fun WriteBuffer.writeProperties(properties: Collection<MqttProperty>?) {
    val list = properties?.toList().orEmpty()
    encodeMqttProperties<ReadBuffer, ReadBuffer>(
        list,
        encodeAuthenticationData = { buf, data ->
            data.position(0)
            buf.write(data)
        },
        encodeCorrelationData = { buf, data ->
            data.position(0)
            buf.write(data)
        },
    )
}
