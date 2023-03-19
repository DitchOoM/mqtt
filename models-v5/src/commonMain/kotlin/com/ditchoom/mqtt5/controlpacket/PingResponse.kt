package com.ditchoom.mqtt5.controlpacket

import com.ditchoom.mqtt.controlpacket.IPingResponse
import com.ditchoom.mqtt.controlpacket.format.fixed.DirectionOfFlow

object PingResponse : ControlPacketV5(13, DirectionOfFlow.SERVER_TO_CLIENT), IPingResponse
