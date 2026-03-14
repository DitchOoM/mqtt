package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.buffer.WriteBuffer
import com.ditchoom.mqtt.controlpacket.IPingResponse
import com.ditchoom.mqtt.controlpacket.format.fixed.DirectionOfFlow

object PingResponse : ControlPacketV4, IPingResponse {
    override val controlPacketValue: Byte get() = 13
    override val direction: DirectionOfFlow get() = DirectionOfFlow.SERVER_TO_CLIENT

    override fun serialize(writeBuffer: WriteBuffer) {
        writeBuffer.writeShort(PINGRESP_PACKED)
    }

    private val PINGRESP_PACKED = 0xD000.toShort()
}
