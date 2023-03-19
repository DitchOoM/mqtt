package com.ditchoom.mqtt.controlpacket

const val NO_PACKET_ID = 0

val validControlPacketIdentifierRange = 1..UShort.MAX_VALUE.toInt()
