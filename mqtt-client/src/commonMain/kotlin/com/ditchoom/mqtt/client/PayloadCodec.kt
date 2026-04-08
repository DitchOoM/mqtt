package com.ditchoom.mqtt.client

import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.payload.PayloadReader

/**
 * Decodes MQTT payload bytes into a consumer type [P].
 *
 * The [PayloadReader] provides zero-copy access to the network buffer slice —
 * bytes are NOT copied into an intermediate ReadBuffer/ByteArray.
 *
 * Covariant: a `PayloadDecoder<ChatMessage>` can be used where `PayloadDecoder<Any>` is expected.
 */
fun interface PayloadDecoder<out P> {
    fun decode(reader: PayloadReader): P
}

/**
 * Encodes a consumer type [P] into MQTT payload bytes.
 *
 * [encode] writes directly into the wire buffer via backpatching — no intermediate allocation.
 * [sizeOf] is used for max-packet-size validation before encoding (optional on hot path).
 *
 * Contravariant: a `PayloadEncoder<Any>` can be used where `PayloadEncoder<ChatMessage>` is expected.
 */
interface PayloadEncoder<in P> {
    fun encode(
        buffer: WriteBuffer,
        value: P,
    )

    fun sizeOf(value: P): Int
}
