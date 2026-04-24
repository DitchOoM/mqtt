package com.ditchoom.mqtt3.persistence

import app.cash.sqldelight.ColumnAdapter
import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ByteOrder
import com.ditchoom.buffer.Default
import com.ditchoom.buffer.ReadBuffer

// Single bridge between SQLDelight BLOB columns and the buffer library.
// SQLDelight's generated driver binding takes ByteArray; the adapter isolates
// that copy to one spot so the rest of v4 persistence speaks ReadBuffer only.
internal object ReadBufferBlobAdapter : ColumnAdapter<ReadBuffer, ByteArray> {
    @Suppress("NoByteArrayInProd") // SQLDelight BLOB driver boundary
    override fun encode(value: ReadBuffer): ByteArray {
        val slice = value.slice()
        return slice.readByteArray(slice.remaining())
    }

    override fun decode(databaseValue: ByteArray): ReadBuffer = BufferFactory.Default.wrap(databaseValue, ByteOrder.BIG_ENDIAN)
}
