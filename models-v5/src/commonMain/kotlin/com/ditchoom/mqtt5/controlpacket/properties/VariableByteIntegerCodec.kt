package com.ditchoom.mqtt5.controlpacket.properties

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.DecodeContext
import com.ditchoom.buffer.codec.EncodeContext
import com.ditchoom.buffer.readVariableByteInteger
import com.ditchoom.buffer.writeVariableByteInteger

object VariableByteIntegerCodec : Codec<Int> {
    override val wireSizeHint: Int get() = 1

    override fun encode(
        buffer: WriteBuffer,
        value: Int,
        context: EncodeContext,
    ) {
        buffer.writeVariableByteInteger(value)
    }

    override fun decode(
        buffer: ReadBuffer,
        context: DecodeContext,
    ): Int = buffer.readVariableByteInteger()
}
