package com.ditchoom.mqtt.client

import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.payload.PayloadReader

/**
 * Decodes MQTT payload bytes into a consumer type [P].
 *
 * The [PayloadReader] is the receiver (`this`), providing zero-copy access to the
 * network buffer slice. The buffer is scoped — it cannot be captured or used after
 * the decode function returns.
 *
 * Covariant: a `PayloadDecoder<ChatMessage>` can be used where `PayloadDecoder<Any>` is expected.
 */
fun interface PayloadDecoder<out P> {
    fun PayloadReader.decode(): P
}

/**
 * Encodes a consumer type [P] into MQTT payload bytes.
 *
 * The [WriteBuffer] is the receiver (`this`), so the encoder writes directly into
 * the wire buffer. The buffer is scoped — it cannot be captured or used after
 * the encode function returns.
 *
 * [size] returns the encoded byte count for a given value, used to allocate
 * the serialization buffer. An exact value avoids reallocation; an upper-bound
 * estimate is acceptable (the buffer will be sliced to actual size).
 *
 * Contravariant: a `PayloadEncoder<Any>` can be used where `PayloadEncoder<ChatMessage>` is expected.
 */
interface PayloadEncoder<in P> {
    fun WriteBuffer.encode(value: P)

    fun size(value: @UnsafeVariance P): Int
}
