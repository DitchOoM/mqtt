package com.ditchoom.mqtt.controlpacket

import com.ditchoom.buffer.BufferFactory
import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.buffer.managed
import com.ditchoom.mqtt.MalformedInvalidVariableByteInteger
import com.ditchoom.mqtt.controlpacket.encoding.readLengthPrefixedUtf8String
import com.ditchoom.mqtt.controlpacket.encoding.writeLengthPrefixedUtf8String
import com.ditchoom.mqtt.controlpacket.format.fixed.DirectionOfFlow
import com.ditchoom.mqtt.controlpacket.encoding.readVariableByteInteger as encodingReadVariableByteInteger
import com.ditchoom.mqtt.controlpacket.encoding.variableByteSize as encodingVariableByteSize
import com.ditchoom.mqtt.controlpacket.encoding.writeVariableByteInteger as encodingWriteVariableByteInteger

interface ControlPacket {
    val controlPacketValue: Byte
    val direction: DirectionOfFlow
    val flags: Byte get() = 0b0
    val mqttVersion: Byte

    val packetIdentifier: Int
        get() = NO_PACKET_ID

    val controlPacketFactory: ControlPacketFactory

    /**
     * The first byte of the fixed header: packet type (bits 7-4) | flags (bits 3-0).
     */
    val byte1: UByte
        get() {
            val packetValueShifted = controlPacketValue.toUInt().shl(4)
            val localFlagsByte = flags.toUByte().toUInt()
            return (packetValueShifted or localFlagsByte).toUByte()
        }

    fun validateOrNull(): ControlPacket? =
        try {
            validateOrThrow()
        } catch (e: Exception) {
            null
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

    /**
     * Encodes the variable header + payload (everything after the fixed header).
     */
    fun encodeBody(writeBuffer: WriteBuffer) {
        variableHeader(writeBuffer)
        payload(writeBuffer)
    }

    fun packetSize() = 1 + encodingVariableByteSize(remainingLength()) + remainingLength()

    /**
     * Byte count of the variable header + payload.
     * Subclasses that use generated codecs should override this.
     */
    fun remainingLength(): Int = 0

    fun serialize(factory: BufferFactory = BufferFactory.managed()): ReadBuffer {
        val size = packetSize()
        val buffer = factory.allocate(size)
        serialize(buffer)
        buffer.resetForRead()
        return buffer
    }

    fun serialize(writeBuffer: WriteBuffer) {
        fixedHeader(writeBuffer)
        encodeBody(writeBuffer)
    }

    companion object {
        /** Maximum fixed header size: 1 byte (byte1) + 4 bytes (max VBI). */
        const val MAX_FIXED_HEADER_SIZE = 5

        fun isValidFirstByte(uByte: UByte): Boolean {
            val byte1AsUInt = uByte.toUInt()
            return byte1AsUInt.shr(4).toInt() in 1..15
        }

        fun WriteBuffer.writeVariableByteInteger(int: Int): WriteBuffer =
            try {
                encodingWriteVariableByteInteger(int)
            } catch (e: IllegalArgumentException) {
                throw MalformedInvalidVariableByteInteger(int)
            }

        fun ReadBuffer.readVariableByteInteger(): Int =
            try {
                encodingReadVariableByteInteger()
            } catch (e: IllegalArgumentException) {
                throw MalformedInvalidVariableByteInteger(0)
            }

        fun variableByteSize(int: Int): Byte =
            try {
                encodingVariableByteSize(int)
            } catch (e: IllegalArgumentException) {
                throw MalformedInvalidVariableByteInteger(int)
            }

        fun WriteBuffer.writeMqttUtf8String(string: String): WriteBuffer = writeLengthPrefixedUtf8String(string)

        fun ReadBuffer.readMqttUtf8StringNotValidatedSized(): Pair<Int, String> = readLengthPrefixedUtf8String()
    }
}
