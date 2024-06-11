package com.ditchoom.mqtt.controlpacket

import com.ditchoom.mqtt.controlpacket.format.ReasonCode

interface IPublishReceived : ControlPacket {
    fun expectedResponse(
        reasonCode: ReasonCode = ReasonCode.SUCCESS,
        reasonString: String? = null,
        userProperty: List<Pair<String, String>> = emptyList(),
    ): IPublishRelease

    companion object {
        const val CONTROL_PACKET_VALUE: Byte = 5
    }
}
