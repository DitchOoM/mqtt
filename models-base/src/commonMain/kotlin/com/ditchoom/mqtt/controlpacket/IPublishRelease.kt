package com.ditchoom.mqtt.controlpacket

import com.ditchoom.mqtt.controlpacket.format.ReasonCode

interface IPublishRelease : ControlPacket {
    fun expectedResponse(
        reasonCode: ReasonCode = ReasonCode.SUCCESS,
        reasonString: String? = null,
        userProperty: List<Pair<String, String>> = emptyList(),
    ): IPublishComplete

    companion object {
        const val CONTROL_PACKET_VALUE: Byte = 6
    }
}
