package com.ditchoom.mqtt.client

import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.ReadBuffer

/**
 * A [ReadBuffer] guard that invalidates after its scope ends.
 *
 * Overrides all abstract [ReadBuffer] members with a validity check + delegation.
 * Non-abstract methods (readShort, readInt, readLong, etc.) use the interface's
 * default implementations which call through our guarded [readByte] and [get].
 *
 * Trade-off: default readShort/readInt/readLong are byte-by-byte instead of
 * platform-optimized (JVM ByteBuffer.getInt, Apple pointer reads, etc.).
 * This is acceptable for a scoped handler payload that is read once.
 * If you need high-performance bulk reads, copy the payload inside the handler
 * via [readByteArray] or [readBytes] (both guarded and delegate to the
 * platform-optimized implementation).
 */
internal class ScopedReadBuffer(private val delegate: ReadBuffer) : ReadBuffer {
    private var valid = true

    fun invalidate() {
        valid = false
    }

    private fun checkValid() {
        check(valid) {
            "Buffer access after scope ended. Copy the payload bytes inside the handler if you need them later."
        }
    }

    // --- PositionBuffer abstract members ---

    override val byteOrder: ByteOrder get() = delegate.byteOrder

    override fun setLimit(limit: Int) {
        checkValid()
        delegate.setLimit(limit)
    }

    override fun limit(): Int = delegate.limit()

    override fun position(): Int = delegate.position()

    override fun position(newPosition: Int) {
        checkValid()
        delegate.position(newPosition)
    }

    // --- ReadBuffer abstract members (guarded + delegate to platform impl) ---

    override fun resetForRead() {
        checkValid()
        delegate.resetForRead()
    }

    override fun readByte(): Byte {
        checkValid()
        return delegate.readByte()
    }

    override fun get(index: Int): Byte {
        checkValid()
        return delegate.get(index)
    }

    override fun slice(): ReadBuffer {
        checkValid()
        return delegate.slice()
    }

    override fun readByteArray(size: Int): ByteArray {
        checkValid()
        return delegate.readByteArray(size)
    }

    override fun readString(length: Int, charset: Charset): String {
        checkValid()
        return delegate.readString(length, charset)
    }

    // --- Non-abstract methods that are commonly used for "copy out" ---
    // These delegate directly to preserve platform-optimized implementations.
    // They are the primary way users should extract data from a scoped payload.

    override fun readBytes(size: Int): ReadBuffer {
        checkValid()
        return delegate.readBytes(size)
    }

    override fun contentEquals(other: ReadBuffer): Boolean {
        checkValid()
        return delegate.contentEquals(other)
    }

    override fun indexOf(needle: ReadBuffer): Int {
        checkValid()
        return delegate.indexOf(needle)
    }

    override fun indexOf(byte: Byte): Int {
        checkValid()
        return delegate.indexOf(byte)
    }

    override fun toString(): String = if (valid) "ScopedReadBuffer($delegate)" else "ScopedReadBuffer(invalidated)"
}
