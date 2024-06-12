package com.ditchoom.mqtt.controlpacket

interface ISubscribeAcknowledgement : ControlPacket {
    companion object {
        const val CONTROL_PACKET_VALUE: Byte = 9
    }
}
