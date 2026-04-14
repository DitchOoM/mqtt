package com.ditchoom.mqtt.controlpacket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.mqtt.controlpacket.format.ReasonCode
import com.ditchoom.mqtt.controlpacket.format.fixed.DirectionOfFlow

/**
 * A PUBLISH Control Packet is sent from a Client to a Server or from Server to a Client to transport an
 * Application Message.
 *
 * The payload bytes are never exposed as a field. To read the payload, call [usePayload]; the lambda
 * receiver is the payload [ReadBuffer] directly, valid only inside the block. To retain payload bytes
 * past the scope, allocate your own buffer inside the block and copy into it.
 *
 * Protocol-version specifics (encoding, v5 properties) live on the v4/v5 subclasses.
 */
abstract class PublishMessage protected constructor(
    val topic: TopicName,
    val qualityOfService: QualityOfService,
    val dup: Boolean,
    val retain: Boolean,
    packetIdentifier: Int,
    protected val storage: PayloadStorage,
) : ControlPacket {
    override val controlPacketValue: Byte get() = CONTROL_PACKET_VALUE
    override val direction: DirectionOfFlow get() = DirectionOfFlow.BIDIRECTIONAL
    override val flags: Byte
        get() {
            val dupInt = if (dup) 0b1000 else 0b0
            val qosInt = qualityOfService.integerValue.toInt().shl(1)
            val retainInt = if (retain) 0b1 else 0b0
            return (dupInt or qosInt or retainInt).toByte()
        }

    final override val packetIdentifier: Int = packetIdentifier

    private var scopeInvalidated = false

    /**
     * Read the payload within a scoped block. The receiver is the payload [ReadBuffer]
     * directly; it is valid only inside [block]. After [block] returns, the scope may be
     * invalidated by the dispatcher.
     *
     * To retain payload bytes past the scope, allocate your own buffer inside the block
     * and copy into it.
     */
    suspend fun <R> usePayload(block: suspend ReadBuffer.() -> R): R {
        check(!scopeInvalidated) { "payload scope has ended" }
        return materializePayload().block()
    }

    /**
     * Non-suspending materialization of the payload for low-level machinery (persistence, IPC,
     * wire encoding). Prefer [usePayload] in consumer code.
     *
     * Returns null only when storage is [PayloadStorage.Bytes] with a null buffer.
     * For [PayloadStorage.Encode], this allocates a scratch buffer sized by [PayloadStorage.Encode.size].
     */
    fun payloadAsReadBufferOrNull(): ReadBuffer? =
        when (val s = storage) {
            is PayloadStorage.Bytes -> s.buffer
            is PayloadStorage.Encode -> {
                val scratch = BufferFactory.Default.allocate(s.size())
                s.write(scratch)
                scratch.resetForRead()
                scratch
            }
        }

    /**
     * Size in bytes of the payload without materializing it. Used by wire encoders to compute
     * the remaining-length field.
     */
    fun payloadSize(): Int =
        when (val s = storage) {
            is PayloadStorage.Bytes -> s.buffer?.remaining() ?: 0
            is PayloadStorage.Encode -> s.size()
        }

    /** Dispatcher calls after all handlers finish. Subsequent [usePayload] calls throw. */
    fun invalidateScope() {
        scopeInvalidated = true
    }

    private fun materializePayload(): ReadBuffer =
        when (val s = storage) {
            is PayloadStorage.Bytes -> s.buffer ?: BufferFactory.Default.allocate(0)
            is PayloadStorage.Encode -> {
                val scratch = BufferFactory.Default.allocate(s.size())
                s.write(scratch)
                scratch.resetForRead()
                scratch
            }
        }

    /** True if this message carries raw payload bytes, false if it carries a typed-encode closure. */
    val hasRawPayloadBytes: Boolean get() = storage is PayloadStorage.Bytes

    abstract fun expectedResponse(
        reasonCode: ReasonCode = ReasonCode.SUCCESS,
        reasonString: String? = null,
        userProperty: List<Pair<String, String>> = emptyList(),
    ): ControlPacket?

    abstract fun setDupFlagNewPubMessage(): PublishMessage

    abstract fun maybeCopyWithNewPacketIdentifier(packetIdentifier: Int): PublishMessage

    companion object {
        const val CONTROL_PACKET_VALUE: Byte = 3
    }
}

/**
 * Internal payload representation. Never exposed on the public surface.
 *
 * - [Bytes] — payload bytes available now. Incoming slices of the network buffer, or outgoing raw
 *   [ReadBuffer] payloads. Zero-copy: the slice is passed to [PublishMessage.usePayload] unmodified.
 *
 * - [Encode] — outgoing typed payload: captures the user's payload + encoder as closures that write
 *   directly into the wire buffer during serialize, preserving the backpatch zero-copy path.
 */
sealed interface PayloadStorage {
    class Bytes(val buffer: ReadBuffer?) : PayloadStorage

    class Encode(
        val write: (WriteBuffer) -> Unit,
        val size: () -> Int,
    ) : PayloadStorage
}
