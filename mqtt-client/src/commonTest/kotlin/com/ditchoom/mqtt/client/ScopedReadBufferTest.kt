package com.ditchoom.mqtt.client

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ScopedReadBufferTest {
    private fun makeBuffer(vararg bytes: Byte): ReadBuffer {
        val buf = BufferFactory.Default.allocate(bytes.size)
        bytes.forEach { buf.writeByte(it) }
        buf.resetForRead()
        return buf
    }

    @Test
    fun readByteWorksWhileValid() {
        val scoped = ScopedReadBuffer(makeBuffer(0x42))
        assertEquals(0x42.toByte(), scoped.readByte())
    }

    @Test
    fun readByteThrowsAfterInvalidation() {
        val scoped = ScopedReadBuffer(makeBuffer(0x42))
        scoped.invalidate()
        assertFailsWith<IllegalStateException> {
            scoped.readByte()
        }
    }

    @Test
    fun getThrowsAfterInvalidation() {
        val scoped = ScopedReadBuffer(makeBuffer(0x42))
        scoped.invalidate()
        assertFailsWith<IllegalStateException> {
            scoped.get(0)
        }
    }

    @Test
    fun readShortThrowsAfterInvalidation() {
        val scoped = ScopedReadBuffer(makeBuffer(0x00, 0x01))
        scoped.invalidate()
        assertFailsWith<IllegalStateException> {
            scoped.readShort()
        }
    }

    @Test
    fun readIntThrowsAfterInvalidation() {
        val scoped = ScopedReadBuffer(makeBuffer(0x00, 0x00, 0x00, 0x01))
        scoped.invalidate()
        assertFailsWith<IllegalStateException> {
            scoped.readInt()
        }
    }

    @Test
    fun readLongThrowsAfterInvalidation() {
        val scoped = ScopedReadBuffer(makeBuffer(0, 0, 0, 0, 0, 0, 0, 1))
        scoped.invalidate()
        assertFailsWith<IllegalStateException> {
            scoped.readLong()
        }
    }

    @Test
    fun readStringThrowsAfterInvalidation() {
        val scoped = ScopedReadBuffer(makeBuffer(0x48, 0x69)) // "Hi"
        scoped.invalidate()
        assertFailsWith<IllegalStateException> {
            scoped.readString(2)
        }
    }

    @Test
    fun readByteArrayThrowsAfterInvalidation() {
        val scoped = ScopedReadBuffer(makeBuffer(0x01, 0x02))
        scoped.invalidate()
        assertFailsWith<IllegalStateException> {
            scoped.readByteArray(2)
        }
    }

    @Test
    fun sliceThrowsAfterInvalidation() {
        val scoped = ScopedReadBuffer(makeBuffer(0x01))
        scoped.invalidate()
        assertFailsWith<IllegalStateException> {
            scoped.slice()
        }
    }

    @Test
    fun readBytesThrowsAfterInvalidation() {
        val scoped = ScopedReadBuffer(makeBuffer(0x01, 0x02))
        scoped.invalidate()
        assertFailsWith<IllegalStateException> {
            scoped.readBytes(2)
        }
    }

    @Test
    fun positionAndLimitReadableAfterInvalidation() {
        val scoped = ScopedReadBuffer(makeBuffer(0x01, 0x02))
        scoped.invalidate()
        // Position and limit are metadata, safe to read
        assertEquals(0, scoped.position())
        assertEquals(2, scoped.limit())
        assertEquals(2, scoped.remaining())
    }

    @Test
    fun positionMutationBlockedAfterInvalidation() {
        val scoped = ScopedReadBuffer(makeBuffer(0x01, 0x02))
        scoped.invalidate()
        assertFailsWith<IllegalStateException> {
            scoped.position(1)
        }
    }

    @Test
    fun readByteArrayWorksWhileValid() {
        val scoped = ScopedReadBuffer(makeBuffer(0x01, 0x02, 0x03))
        val arr = scoped.readByteArray(3)
        assertEquals(3, arr.size)
        assertEquals(0x01.toByte(), arr[0])
        assertEquals(0x03.toByte(), arr[2])
    }

    @Test
    fun sliceCopiedInsideScopeRemainsUsable() {
        val scoped = ScopedReadBuffer(makeBuffer(0x41, 0x42))
        // Copy out data while valid
        val copy = scoped.readBytes(2)
        scoped.invalidate()
        // The copy is independent — still usable after invalidation
        assertEquals(0x41.toByte(), copy.readByte())
        assertEquals(0x42.toByte(), copy.readByte())
    }

    @Test
    fun errorMessageIsDescriptive() {
        val scoped = ScopedReadBuffer(makeBuffer(0x01))
        scoped.invalidate()
        val ex = assertFailsWith<IllegalStateException> { scoped.readByte() }
        assertTrue(ex.message!!.contains("Copy the payload bytes"))
    }
}
