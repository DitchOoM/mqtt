package com.ditchoom.mqtt.controlpacket

interface IConnectionAcknowledgment : ControlPacket {
    val isSuccessful: Boolean
    val connectionReason: String
    val sessionPresent: Boolean

    val sessionExpiryInterval: ULong get() = 0uL
    val receiveMaximum: Int get() = UShort.MAX_VALUE.toInt()
    val maximumQos: QualityOfService get() = QualityOfService.EXACTLY_ONCE
    val maxPacketSize: ULong get() = ULong.MAX_VALUE
    val assignedClientIdentifier: String? get() = null
    val serverKeepAlive: Int get() = -1
}
