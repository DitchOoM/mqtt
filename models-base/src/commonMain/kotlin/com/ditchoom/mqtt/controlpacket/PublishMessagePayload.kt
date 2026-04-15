package com.ditchoom.mqtt.controlpacket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.mqtt.codec.PayloadCodec

/**
 * Materialize a [PublishMessage]'s payload as a [ReadBuffer] using its attached codec.
 *
 * Used by persistence/IPC machinery that needs the raw wire bytes of the payload. Returns
 * `null` when the payload is empty (size 0).
 *
 * Callers must not retain the returned buffer across buffer-pool boundaries; copy the bytes
 * out (e.g., via [ReadBuffer.readByteArray]) if long-term retention is needed.
 */
fun PublishMessage.payloadAsReadBufferOrNull(): ReadBuffer? {
    val materializer = this as? PublishMessagePayloadMaterializer<*> ?: return null
    return materializer.materializePayload()
}

/**
 * Contract for PUBLISH implementations to expose their payload as raw bytes for persistence
 * and IPC. Each concrete v4/v5 PublishMessage type implements this.
 *
 * Not intended for direct use by application code — prefer [payloadAsReadBufferOrNull] or
 * [encodePayloadTo].
 *
 * This is the generic typed form of [PublishMessage]. Client code that cares about the
 * decoded payload type `P` can depend on this interface directly (via typed subscribe /
 * observe APIs), while untyped machinery (dispatchers, persistence, wire encoding) depends
 * on the non-generic [PublishMessage] marker.
 */
interface PublishMessagePayloadMaterializer<P> : PublishMessage {
    val payload: P
    val codec: PayloadCodec<P>

    fun materializePayload(): ReadBuffer? {
        val size = codec.encodedSize(payload)
        if (size == 0) return null
        val buf = BufferFactory.Default.allocate(size)
        codec.encode(buf, payload)
        buf.resetForRead()
        return buf
    }
}

/**
 * Writes a [PublishMessage]'s payload directly to [out] using its attached codec.
 * Zero-copy if the codec doesn't allocate internally.
 */
fun PublishMessage.encodePayloadTo(out: WriteBuffer) {
    val materializer = this as? PublishMessagePayloadMaterializer<*> ?: return
    @Suppress("UNCHECKED_CAST")
    (materializer as PublishMessagePayloadMaterializer<Any?>).let { m ->
        m.codec.encode(out, m.payload)
    }
}
