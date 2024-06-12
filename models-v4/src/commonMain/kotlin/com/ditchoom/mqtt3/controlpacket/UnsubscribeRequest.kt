package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.mqtt.ProtocolError
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.readMqttUtf8StringNotValidatedSized
import com.ditchoom.mqtt.controlpacket.ControlPacket.Companion.writeMqttUtf8String
import com.ditchoom.mqtt.controlpacket.IUnsubscribeRequest
import com.ditchoom.mqtt.controlpacket.Topic
import com.ditchoom.mqtt.controlpacket.format.fixed.DirectionOfFlow
import com.ditchoom.mqtt.controlpacket.utf8Length

/**
 * 3.10 UNSUBSCRIBE – Unsubscribe request
 * An UNSUBSCRIBE packet is sent by the Client to the Server, to unsubscribe from topics.
 */
data class UnsubscribeRequest(
    override val packetIdentifier: Int,
    override val topics: Set<Topic>,
) : ControlPacketV4(IUnsubscribeRequest.controlPacketValue, DirectionOfFlow.CLIENT_TO_SERVER, 0b10),
    IUnsubscribeRequest {
    constructor(packetIdentifier: Int, topicString: Collection<String>) :
        this(packetIdentifier, topicString.map { Topic.fromOrThrow(it, Topic.Type.Filter) }.toSet())

    override fun remainingLength() = UShort.SIZE_BYTES + payloadSize()

    override fun variableHeader(writeBuffer: WriteBuffer) {
        writeBuffer.writeUShort(packetIdentifier.toUShort())
    }

    private fun payloadSize(): Int {
        var size = 0
        topics.forEach {
            size += UShort.SIZE_BYTES + it.toString().utf8Length()
        }
        return size
    }

    override fun payload(writeBuffer: WriteBuffer) {
        topics.forEach { writeBuffer.writeMqttUtf8String(it.toString()) }
    }

    init {
        if (topics.isEmpty()) {
            throw ProtocolError("An UNSUBSCRIBE packet with no Payload is a Protocol Error")
        }
    }

    override fun copyWithNewPacketIdentifier(packetIdentifier: Int): IUnsubscribeRequest = copy(packetIdentifier = packetIdentifier)

    companion object {
        fun from(
            buffer: ReadBuffer,
            remainingLength: Int,
        ): UnsubscribeRequest {
            val packetIdentifier = buffer.readUnsignedShort()
            val topics = mutableSetOf<Topic>()
            var bytesRead = 0
            while (bytesRead < remainingLength - 2) {
                val pair = buffer.readMqttUtf8StringNotValidatedSized()
                bytesRead += 2 + pair.first
                topics += Topic.fromOrThrow(pair.second, Topic.Type.Filter)
            }
            return UnsubscribeRequest(packetIdentifier.toInt(), topics)
        }
    }
}
