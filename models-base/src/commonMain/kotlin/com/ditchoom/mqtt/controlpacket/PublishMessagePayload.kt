package com.ditchoom.mqtt.controlpacket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.mqtt.codec.IdentityBufferCodec
import com.ditchoom.mqtt.codec.PayloadCodec

/**
 * Contract for PUBLISH implementations carrying a typed payload + its codec. Each
 * concrete v4/v5 PublishMessage type implements this. Not intended for direct use by
 * application code — prefer [rawPayload], [payloadAsByteArrayOrNull], or [encodePayloadTo].
 */
interface PublishMessagePayloadMaterializer<P> : PublishMessage {
    val payload: P
    val codec: PayloadCodec<P>
}

/**
 * Shared reference to the wire-bytes payload, non-null only when the payload is
 * already a [ReadBuffer] (i.e. a wire-decoded or `ofRaw`-constructed publish). The
 * returned buffer shares position/limit with the message's stored payload — if
 * you need independent read progress (e.g. multi-subscriber dispatch), call
 * [ReadBuffer.slice] on the result.
 *
 * Zero-copy: no bytes are read, allocated, or moved. For persistence and IPC paths
 * that need a `ByteArray`, use [payloadAsByteArrayOrNull] instead.
 */
fun PublishMessage.rawPayload(): ReadBuffer? {
    val m = this as? PublishMessagePayloadMaterializer<*> ?: return null
    return if (m.codec === IdentityBufferCodec) m.payload as ReadBuffer else null
}

/**
 * Payload as bytes for storage (SQLite BLOB / IndexedDB) or IPC (Android AIDL parcel).
 * When the underlying payload is already a [ReadBuffer] (identity codec), bytes are
 * copied directly from a zero-copy slice. Otherwise the codec is invoked to encode
 * the typed payload.
 *
 * Returns `null` for zero-size payloads.
 *
 * This function is a deliberate platform-boundary ByteArray exit point —
 * SQLDelight BLOB binding, IndexedDB, and Android AIDL all consume bytes, and
 * their driver APIs ultimately require a `ByteArray` (JDBC
 * `setBytes(int, byte[])`, `Int8Array.unsafeCast<ByteArray>`, AIDL
 * `writeByteArray`). A zero-copy replacement would require a custom
 * SQLDelight `ColumnAdapter<ReadBuffer, ByteArray>` plus per-platform
 * driver plumbing — deferred to the Phase 4 ownership/borrowed buffer
 * design cycle. Callers that don't need bytes should use [rawPayload]
 * (zero-copy) instead.
 */
@Suppress("NoByteArrayInProd") // platform boundary: SQL BLOB / IDB / AIDL all take ByteArray
fun PublishMessage.payloadAsByteArrayOrNull(): ByteArray? {
    val m = this as? PublishMessagePayloadMaterializer<*> ?: return null
    if (m.codec === IdentityBufferCodec) {
        val raw = m.payload as ReadBuffer
        val size = raw.remaining()
        if (size == 0) return null
        return raw.slice().readByteArray(size)
    }
    @Suppress("UNCHECKED_CAST")
    val mAny = m as PublishMessagePayloadMaterializer<Any?>
    val size = mAny.codec.encodedSize(mAny.payload)
    if (size == 0) return null
    val buf = BufferFactory.Default.allocate(size)
    mAny.codec.encode(buf, mAny.payload)
    buf.resetForRead()
    return buf.readByteArray(size)
}

/**
 * Payload as a [ReadBuffer] for persistence paths that store buffers (the SQLDelight
 * `ColumnAdapter<ReadBuffer, ByteArray>` pipeline). Zero-copy when the underlying
 * payload is already a `ReadBuffer` (identity codec); otherwise the codec encodes
 * into a fresh buffer. Returns `null` for zero-size payloads.
 *
 * Unlike [payloadAsByteArrayOrNull], no `ByteArray` materialises here — the adapter
 * converts to `ByteArray` inside the driver boundary.
 */
fun PublishMessage.payloadAsReadBufferOrNull(): ReadBuffer? {
    val m = this as? PublishMessagePayloadMaterializer<*> ?: return null
    if (m.codec === IdentityBufferCodec) {
        val raw = m.payload as ReadBuffer
        if (raw.remaining() == 0) return null
        return raw.slice()
    }
    @Suppress("UNCHECKED_CAST")
    val mAny = m as PublishMessagePayloadMaterializer<Any?>
    val size = mAny.codec.encodedSize(mAny.payload)
    if (size == 0) return null
    val buf = BufferFactory.Default.allocate(size)
    mAny.codec.encode(buf, mAny.payload)
    buf.resetForRead()
    return buf
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
