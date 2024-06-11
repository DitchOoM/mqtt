package com.ditchoom.mqtt5.persistence

data class Qos2Message(
    val packetId: Int,
    val controlPacketValue: Byte,
)
