package com.ditchoom.mqtt.controlpacket

interface IUnsubscribeRequest : ControlPacket {
    val topics: Set<Topic>
    fun copyWithNewPacketIdentifier(packetIdentifier: Int): IUnsubscribeRequest
}
