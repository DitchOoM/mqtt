package com.ditchoom.mqtt.codec

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer

/**
 * Payload codec for MQTT PUBLISH messages.
 *
 * Application code supplies a [PayloadCodec] when publishing a typed `P` via
 * [com.ditchoom.mqtt3.controlpacket.PublishMessageV4.ofTyped] / `PublishMessageV5.ofTyped`,
 * and when subscribing with a typed handler.
 *
 * For raw bytes (`P = ReadBuffer`), use [IdentityBufferCodec].
 */
interface PayloadCodec<P> {
    /**
     * Decode a value from [buffer]. The buffer's current position and limit bound the
     * payload region — read up to [ReadBuffer.remaining] bytes.
     */
    fun decode(buffer: ReadBuffer): P

    /**
     * Write [value] into [buffer] at its current position.
     */
    fun encode(
        buffer: WriteBuffer,
        value: P,
    )

    /**
     * Byte size of [value] when encoded. Used to compute the MQTT remaining-length
     * VBI framing before the payload is written.
     */
    fun encodedSize(value: P): Int
}

/**
 * Treats `P = ReadBuffer` as opaque bytes. Reads remaining on decode, writes the
 * buffer verbatim on encode.
 */
object IdentityBufferCodec : PayloadCodec<ReadBuffer> {
    override fun decode(buffer: ReadBuffer): ReadBuffer = buffer.readBytes(buffer.remaining())

    override fun encode(
        buffer: WriteBuffer,
        value: ReadBuffer,
    ) {
        buffer.write(value)
    }

    override fun encodedSize(value: ReadBuffer): Int = value.remaining()
}
