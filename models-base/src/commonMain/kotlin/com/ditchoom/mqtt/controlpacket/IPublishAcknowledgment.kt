package com.ditchoom.mqtt.controlpacket

interface IPublishAcknowledgment : ControlPacket {
    companion object {
        const val controlPacketValue: Byte = 4
    }
}
