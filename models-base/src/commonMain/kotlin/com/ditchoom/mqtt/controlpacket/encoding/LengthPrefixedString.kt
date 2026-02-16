package com.ditchoom.mqtt.controlpacket.encoding

import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer

/**
 * Writes a length-prefixed UTF-8 string: 2-byte big-endian length prefix followed by UTF-8 data.
 *
 * This encoding is used by MQTT for topic names, client IDs, and other string fields.
 */
fun WriteBuffer.writeLengthPrefixedUtf8String(string: String): WriteBuffer {
    val sizePosition = position()
    position(sizePosition + UShort.SIZE_BYTES)
    val startStringPosition = position()
    writeString(string, Charset.UTF8)
    val stringLength = (position() - startStringPosition).toUShort()
    set(sizePosition, stringLength)
    return this
}

/**
 * Reads a length-prefixed UTF-8 string: 2-byte big-endian length prefix followed by UTF-8 data.
 *
 * @return a [Pair] of (byte length, decoded string)
 */
fun ReadBuffer.readLengthPrefixedUtf8String(): Pair<Int, String> {
    val length = readUnsignedShort().toInt()
    val decoded = readString(length, Charset.UTF8)
    return Pair(length, decoded)
}
