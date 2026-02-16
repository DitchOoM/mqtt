package com.ditchoom.mqtt.client

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.ReadBuffer.Companion.EMPTY_BUFFER
import com.ditchoom.buffer.VARIABLE_BYTE_INT_MAX
import com.ditchoom.mqtt.MalformedInvalidVariableByteInteger
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.ControlPacketFactory
import com.ditchoom.mqtt.controlpacket.IDisconnectNotification
import kotlinx.coroutines.flow.flow
import kotlin.experimental.and
import kotlin.time.Duration

class BufferedControlPacketReader(
    private val brokerId: Int,
    private val factory: ControlPacketFactory,
    private val transport: MqttTransport,
    private val readTimeout: Duration,
    var observer: Observer? = null,
    private var incomingMessage: (UByte, Int, ReadBuffer) -> Unit,
) {
    val incomingControlPackets =
        flow {
            try {
                while (transport.isOpen()) {
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
                incomingMessage = { _, _, _ -> }
                observer?.onReaderClosed(brokerId, factory.protocolVersion.toByte())
            }
        }

    fun isOpen() = transport.isOpen()

    internal suspend fun readControlPacket(): ControlPacket {
        transport.readIntoStream(readTimeout)
        val byte1 = transport.stream.readUnsignedByte().toUByte()
        observer?.readFirstByteFromStream(brokerId, factory.protocolVersion.toByte())
        val remainingLength = readVariableByteInteger()
        val buffer =
            if (remainingLength < 1) {
                EMPTY_BUFFER
            } else {
                ensureAvailable(remainingLength)
                transport.stream.readBuffer(remainingLength)
            }
        val packet =
            factory.from(
                buffer,
                byte1,
                remainingLength,
            )
        buffer.resetForRead()
        incomingMessage(byte1, remainingLength, buffer)
        observer?.incomingPacket(brokerId, factory.protocolVersion.toByte(), packet)
        return packet
    }

    private suspend fun ensureAvailable(minBytes: Int) {
        while (transport.stream.available() < minBytes) {
            transport.readIntoStream(readTimeout)
        }
    }

    private suspend fun readVariableByteInteger(): Int {
        var digit: Byte
        var value = 0L
        var multiplier = 1L
        try {
            do {
                if (transport.stream.available() < 1) {
                    transport.readIntoStream(readTimeout)
                }
                digit = transport.stream.readByte()
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
}
