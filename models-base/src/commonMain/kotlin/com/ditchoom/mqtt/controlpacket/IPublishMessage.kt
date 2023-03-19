package com.ditchoom.mqtt.controlpacket

import com.ditchoom.buffer.ReadBuffer
import com.ditchoom.mqtt.controlpacket.format.ReasonCode

interface IPublishMessage : ControlPacket {
    val qualityOfService: QualityOfService
    val topic: Topic
    val payload: ReadBuffer?

    fun expectedResponse(
        reasonCode: ReasonCode = ReasonCode.SUCCESS,
        reasonString: String? = null,
        userProperty: List<Pair<String, String>> = emptyList()
    ): ControlPacket?

    fun setDupFlagNewPubMessage(): IPublishMessage
    fun maybeCopyWithNewPacketIdentifier(packetIdentifier: Int): IPublishMessage

    companion object {
        const val controlPacketValue: Byte = 3
    }
}
