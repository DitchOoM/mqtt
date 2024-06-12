package com.ditchoom.mqtt.controlpacket

interface IPublishComplete : ControlPacket {
    companion object {
        const val CONTROL_PACKET_VALUE: Byte = 7
    }
}
