package com.ditchoom.mqtt.controlpacket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer

/**
 * Payload as bytes for storage (SQLite BLOB / IndexedDB) or IPC (Android AIDL parcel).
 * Returns `null` for zero-size payloads (or when the message is a typed-payload
 * `V5Packet.Publish<P>` whose `P` is not a [ReadBuffer]).
 *
 * This function is a deliberate platform-boundary ByteArray exit point —
 * SQLDelight BLOB binding, IndexedDB, and Android AIDL all consume bytes, and
 * their driver APIs ultimately require a `ByteArray` (JDBC
 * `setBytes(int, byte[])`, `Int8Array.unsafeCast<ByteArray>`, AIDL
 * `writeByteArray`). A zero-copy replacement would require a custom
 * SQLDelight `ColumnAdapter<ReadBuffer, ByteArray>` plus per-platform
 * driver plumbing — deferred to the Phase 4 ownership/borrowed buffer
 * design cycle. Callers that don't need bytes should use [PublishMessage.rawPayload]
 * (zero-copy) instead.
 */
@Suppress("NoByteArrayInProd") // platform boundary: SQL BLOB / IDB / AIDL all take ByteArray
fun PublishMessage.payloadAsByteArrayOrNull(): ByteArray? {
    val raw = rawPayload() ?: return null
    val size = raw.remaining()
    if (size == 0) return null
    return raw.slice().readByteArray(size)
}

/**
 * Payload as a [ReadBuffer] for persistence paths that store buffers (the SQLDelight
 * `ColumnAdapter<ReadBuffer, ByteArray>` pipeline). Zero-copy slice. Returns `null`
 * for zero-size payloads (or typed-payload variants whose `P` is not a [ReadBuffer]).
 *
 * Unlike [payloadAsByteArrayOrNull], no `ByteArray` materialises here — the adapter
 * converts to `ByteArray` inside the driver boundary.
 */
fun PublishMessage.payloadAsReadBufferOrNull(): ReadBuffer? {
    val raw = rawPayload() ?: return null
    if (raw.remaining() == 0) return null
    return raw.slice()
}

/**
 * Writes a [PublishMessage]'s payload directly to [out]. Zero-copy slice transfer.
 */
fun PublishMessage.encodePayloadTo(out: WriteBuffer) {
    val raw = rawPayload() ?: return
    out.write(raw.slice())
}
