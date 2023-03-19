package com.ditchoom.mqtt5.controlpacket

import com.ditchoom.mqtt.controlpacket.IReserved
import com.ditchoom.mqtt.controlpacket.format.fixed.DirectionOfFlow

object Reserved : ControlPacketV5(0, DirectionOfFlow.FORBIDDEN), IReserved
