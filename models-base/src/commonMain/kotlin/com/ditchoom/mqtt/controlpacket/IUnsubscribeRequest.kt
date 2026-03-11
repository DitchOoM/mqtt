package com.ditchoom.mqtt.controlpacket

interface IUnsubscribeRequest : ControlPacket {
    val topics: Set<TopicFilter>

    fun copyWithNewPacketIdentifier(packetIdentifier: Int): IUnsubscribeRequest

    companion object {
        val controlPacketValue = 10.toByte()
    }
}
