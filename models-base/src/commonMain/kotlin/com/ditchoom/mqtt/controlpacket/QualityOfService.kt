package com.ditchoom.mqtt.controlpacket

import com.ditchoom.mqtt.MalformedPacketException

enum class QualityOfService(val integerValue: Byte) {
    AT_MOST_ONCE(0),
    AT_LEAST_ONCE(1),
    EXACTLY_ONCE(2),
    ;

    fun isGreaterThan(otherQos: QualityOfService) = integerValue > otherQos.integerValue

    companion object {
        fun fromBooleans(
            bit2: Boolean,
            bit1: Boolean,
        ): QualityOfService {
            return if (bit2 && !bit1) {
                EXACTLY_ONCE
            } else if (!bit2 && bit1) {
                AT_LEAST_ONCE
            } else if (!bit2 && !bit1) {
                AT_MOST_ONCE
            } else {
                throw MalformedPacketException("Invalid flags received, 0x03. Double check QOS is not set to 0x03")
            }
        }
    }
}
