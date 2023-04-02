package com.ditchoom.mqtt.client

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.data.Reader
import com.ditchoom.mqtt.MalformedInvalidVariableByteInteger
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.ControlPacketFactory
import com.ditchoom.mqtt.controlpacket.IDisconnectNotification
import com.ditchoom.socket.EMPTY_BUFFER
import com.ditchoom.socket.SuspendingSocketInputStream
import kotlinx.coroutines.flow.flow
import kotlin.experimental.and
import kotlin.time.Duration

class BufferedControlPacketReader(
    private val brokerId: Int,
    private val factory: ControlPacketFactory,
    readTimeout: Duration,
    private val reader: Reader,
    var observer: Observer? = null,
    private val incomingMessage: (UByte, Int, ReadBuffer) -> Unit
) {
    private val inputStream = SuspendingSocketInputStream(readTimeout, reader)
    val incomingControlPackets = flow {
        try {
            while (reader.isOpen()) {
                try {
                    val p = readControlPacket()
                    emit(p)
                    if (p is IDisconnectNotification) {
                        return@flow
                    }
                } catch (e: Exception) {
                    return@flow
                }
            }
        } finally {
            observer?.onReaderClosed(brokerId, factory.protocolVersion.toByte())
        }
    }

    fun isOpen() = reader.isOpen()

    internal suspend fun readControlPacket(): ControlPacket {
        val byte1 = inputStream.readUnsignedByte()
        observer?.readFirstByteFromStream(brokerId, factory.protocolVersion.toByte())
        val remainingLength = readVariableByteInteger()
        val buffer = if (remainingLength < 1) {
            EMPTY_BUFFER
        } else {
            inputStream.readBuffer(remainingLength)
        }
        val packet = factory.from(
            buffer,
            byte1,
            remainingLength
        )
        buffer.resetForRead()
        incomingMessage(byte1, remainingLength, buffer)
        observer?.incomingPacket(brokerId, factory.protocolVersion.toByte(), packet)
        return packet
    }

    private suspend fun readVariableByteInteger(): Int {
        var digit: Byte
        var value = 0L
        var multiplier = 1L
        var count = 0L
        try {
            do {
                digit = inputStream.readByte()
                count++
                value += (digit and 0x7F).toLong() * multiplier
                multiplier *= 128
            } while ((digit and 0x80.toByte()).toInt() != 0)
        } catch (e: Exception) {
            throw MalformedInvalidVariableByteInteger(value.toInt())
        }
        val variableByteIntMax = 268435455L
        if (value < 0 || value > variableByteIntMax) {
            throw MalformedInvalidVariableByteInteger(value.toInt())
        }
        return value.toInt()
    }
}
