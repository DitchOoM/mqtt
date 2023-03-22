package com.ditchoom.mqtt.controlpacket

import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.PlatformBuffer
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.allocate
import com.ditchoom.mqtt.MalformedInvalidVariableByteInteger
import com.ditchoom.mqtt.controlpacket.format.fixed.DirectionOfFlow
import kotlin.experimental.and
import kotlin.experimental.or

interface ControlPacket {
    val controlPacketValue: Byte
    val direction: DirectionOfFlow
    val flags: Byte get() = 0b0
    val mqttVersion: Byte

    val packetIdentifier: Int
        get() = NO_PACKET_ID

    val controlPacketFactory: ControlPacketFactory

    fun validateOrNull(): ControlPacket? {
        return try {
            validateOrThrow()
        } catch (e: Exception) {
            null
        }
    }

    fun validateOrThrow(): ControlPacket {
        val exception = validate() ?: return this
        throw exception
    }

    fun validate(): Exception? = null

    private fun fixedHeader(writeBuffer: WriteBuffer) {
        val packetValueUInt = controlPacketValue.toUInt()
        val packetValueShifted = packetValueUInt.shl(4)
        val localFlagsByte = flags.toUByte().toInt()
        val byte1 = (packetValueShifted.toByte() + localFlagsByte).toUByte()
        writeBuffer.writeUByte(byte1)
        val remaining = remainingLength()
        writeBuffer.writeVariableByteInteger(remaining)
    }

    fun variableHeader(writeBuffer: WriteBuffer) {}
    fun payload(writeBuffer: WriteBuffer) {}
    fun packetSize() = 2 + remainingLength()
    fun remainingLength() = 0

    fun serialize(): PlatformBuffer {
        val size = packetSize()
        val buffer = PlatformBuffer.allocate(size)
        serialize(buffer)
        return buffer
    }

    fun serialize(writeBuffer: WriteBuffer) {
        fixedHeader(writeBuffer)
        variableHeader(writeBuffer)
        payload(writeBuffer)
    }

    companion object {

        private const val VARIABLE_BYTE_INT_MAX = 268435455

        fun isValidFirstByte(uByte: UByte): Boolean {
            val byte1AsUInt = uByte.toUInt()
            return byte1AsUInt.shr(4).toInt() in 1..15
        }

        fun WriteBuffer.writeVariableByteInteger(int: Int): WriteBuffer {
            if (int !in 0..VARIABLE_BYTE_INT_MAX) {
                throw MalformedInvalidVariableByteInteger(int)
            }
            var numBytes = 0
            var no = int.toLong()
            do {
                var digit = (no % 128).toByte()
                no /= 128
                if (no > 0) {
                    digit = digit or 0x80.toByte()
                }
                writeByte(digit)
                numBytes++
            } while (no > 0 && numBytes < 4)
            return this
        }

        fun WriteBuffer.writeMqttUtf8String(string: String): WriteBuffer {
            val sizePosition = position()
            position(sizePosition + UShort.SIZE_BYTES)
            val startStringPosition = position()
            writeString(string, Charset.UTF8)
            val stringLength = (position() - startStringPosition).toUShort()
            set(sizePosition, stringLength)
            return this
        }

        fun ReadBuffer.readMqttUtf8StringNotValidatedSized(): Pair<Int, String> {
            val length = readUnsignedShort().toInt()
            val decoded = readString(length, Charset.UTF8)
            return Pair(length, decoded)
        }

        fun ReadBuffer.readVariableByteInteger(): Int {
            var digit: Byte
            var value = 0L
            var multiplier = 1L
            var count = 0L
            try {
                do {
                    digit = readByte()
                    count++
                    value += (digit and 0x7F).toLong() * multiplier
                    multiplier *= 128
                } while ((digit and 0x80.toByte()).toInt() != 0)
            } catch (e: Exception) {
                throw MalformedInvalidVariableByteInteger(value.toInt())
            }
            if (value < 0 || value > VARIABLE_BYTE_INT_MAX.toLong()) {
                throw MalformedInvalidVariableByteInteger(value.toInt())
            }
            return value.toInt()
        }

        fun variableByteSize(int: Int): Byte {
            if (int !in 0..VARIABLE_BYTE_INT_MAX) {
                throw MalformedInvalidVariableByteInteger(int)
            }
            var numBytes = 0.toByte()
            var no = int
            do {
                no /= 128
                numBytes++
            } while (no > 0 && numBytes < 4)
            return numBytes
        }
    }
}
