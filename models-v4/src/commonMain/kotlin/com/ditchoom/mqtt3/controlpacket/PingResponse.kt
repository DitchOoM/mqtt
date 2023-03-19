package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.mqtt.controlpacket.IPingResponse
import com.ditchoom.mqtt.controlpacket.format.fixed.DirectionOfFlow

object PingResponse : ControlPacketV4(13, DirectionOfFlow.SERVER_TO_CLIENT), IPingResponse
