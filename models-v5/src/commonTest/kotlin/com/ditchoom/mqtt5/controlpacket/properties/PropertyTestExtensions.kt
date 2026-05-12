package com.ditchoom.mqtt5.controlpacket.properties

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer

/**
 * Test-only conveniences for round-tripping the MQTT v5 property section without
 * constructing a full PUBLISH/CONNECT/etc. packet. Production code goes through
 * the generated codec dispatcher.
 *
 * Phase A intermediary: CorrelationData / AuthenticationData carry `String` payloads
 * (lossy UTF-8 decode of bytes). The dedicated binary-data shape returns with the
 * Phase B typed-payload design pick.
 */
fun ReadBuffer.readProperties(): Collection<MqttProperty>? {
    val result = decodeMqttProperties()
    return result.ifEmpty { null }
}

fun WriteBuffer.writeProperties(properties: Collection<MqttProperty>?) {
    val list = properties?.toList().orEmpty()
    encodeMqttProperties(list)
}
