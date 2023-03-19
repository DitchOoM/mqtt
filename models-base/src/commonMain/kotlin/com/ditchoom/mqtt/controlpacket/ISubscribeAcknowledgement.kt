package com.ditchoom.mqtt.controlpacket

interface ISubscribeAcknowledgement : ControlPacket {
    companion object {
        const val controlPacketValue: Byte = 9
    }
}
