package com.ditchoom.mqtt.controlpacket

interface IPublishAcknowledgment : ControlPacket {
    companion object {
        const val CONTROL_PACKET_VALUE: Byte = 4
    }
}
