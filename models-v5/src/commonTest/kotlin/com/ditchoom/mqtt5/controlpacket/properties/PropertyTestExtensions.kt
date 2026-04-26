package com.ditchoom.mqtt5.controlpacket.properties

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer

/**
 * Test-only conveniences for round-tripping the MQTT v5 property section without
 * constructing a full PUBLISH/CONNECT/etc. packet. Production code goes through
 * the generated codec dispatcher (with an EncodeContext/DecodeContext supplied by
 * the surrounding control packet).
 *
 * Binary-data variants (CorrelationData / AuthenticationData) materialize their
 * `data` field as a copied `ReadBuffer` slice, matching the wire-payload semantics
 * the generated codec uses when no caller-supplied lambda overrides the default.
 */
fun ReadBuffer.readProperties(): Collection<MqttProperty>? {
    val result =
        decodeMqttProperties<ReadBuffer, ReadBuffer>(
            decodeAuthenticationData = { reader -> reader.copyToBuffer() },
            decodeCorrelationData = { reader -> reader.copyToBuffer() },
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
