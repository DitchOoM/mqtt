package com.ditchoom.mqtt.controlpacket

interface ISubscribeRequest : ControlPacket {
    fun expectedResponse(): ISubscribeAcknowledgement

    val subscriptions: Set<ISubscription>

    fun copyWithNewPacketIdentifier(packetIdentifier: Int): ISubscribeRequest

    companion object {
        const val CONTROL_PACKET_VALUE: Byte = 8
    }
}
