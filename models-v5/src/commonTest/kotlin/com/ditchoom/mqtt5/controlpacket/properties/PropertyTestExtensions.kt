package com.ditchoom.mqtt5.controlpacket.properties

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.OwnedBytesHandle
import com.ditchoom.buffer.codec.ownedBytesFrom

/**
 * Test-only conveniences for round-tripping the MQTT v5 property section without
 * constructing a full PUBLISH/CONNECT/etc. packet. Production code goes through
 * the generated codec dispatcher.
 */
fun ReadBuffer.readProperties(): Collection<MqttProperty>? {
    val result = decodeMqttProperties()
    return result.ifEmpty { null }
}

fun WriteBuffer.writeProperties(properties: Collection<MqttProperty>?) {
    val list = properties?.toList().orEmpty()
    encodeMqttProperties(list)
}

/**
 * Test helper: build an [OwnedBytesHandle] from a String literal by UTF-8 encoding.
 * Tests that just want a stable byte pattern call this; tests asserting byte-exact
 * non-UTF-8 content build their own [OwnedBytesHandle] from a raw ByteArray via
 * `ownedBytesFrom(BufferFactory.Default.wrap(bytes))`.
 */
fun ownedBytesFromUtf8(text: String): OwnedBytesHandle {
    val bytes = text.encodeToByteArray()
    val dst = BufferFactory.Default.allocate(bytes.size)
    dst.writeBytes(bytes)
    dst.resetForRead()
    return ownedBytesFrom(dst)
}
