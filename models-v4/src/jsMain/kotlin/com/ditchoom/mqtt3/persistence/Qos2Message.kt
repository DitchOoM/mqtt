package com.ditchoom.mqtt3.persistence

data class Qos2Message(
    val packetId: Int,
    val controlPacketValue: Byte
)
