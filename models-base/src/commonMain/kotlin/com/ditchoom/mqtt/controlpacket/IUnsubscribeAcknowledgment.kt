package com.ditchoom.mqtt.controlpacket

interface IUnsubscribeAcknowledgment : ControlPacket {
    companion object {
        const val CONTROL_PACKET_VALUE: Byte = 11
    }
}
