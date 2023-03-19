package com.ditchoom.mqtt.controlpacket

interface IPublishComplete : ControlPacket {
    companion object {
        const val controlPacketValue: Byte = 7
    }
}
