package com.ditchoom.mqtt5.controlpacket

import com.ditchoom.mqtt.controlpacket.IReserved
import com.ditchoom.mqtt.controlpacket.format.fixed.DirectionOfFlow

object Reserved : ControlPacketV5, IReserved {
    override val controlPacketValue: Byte get() = 0
    override val direction: DirectionOfFlow get() = DirectionOfFlow.FORBIDDEN
}
