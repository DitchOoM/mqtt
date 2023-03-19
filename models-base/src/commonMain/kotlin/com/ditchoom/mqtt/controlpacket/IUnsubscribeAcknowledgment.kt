package com.ditchoom.mqtt.controlpacket

interface IUnsubscribeAcknowledgment : ControlPacket {
    companion object {
        const val controlPacketValue: Byte = 11
    }
}
