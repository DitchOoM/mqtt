package com.ditchoom.mqtt.client

import kotlin.jvm.JvmInline

/**
 * Maximum number of unacknowledged outbound QoS 1/2 PUBLISH packets. Each session uses the
 * smaller of the configured limit and the broker's CONNACK Receive Maximum ([MQTT-3.3.4-9]);
 * MQTT 3.1.1 brokers advertise none, so the configured limit alone applies.
 */
@JvmInline
value class InFlightLimit(
    val value: Int,
) {
    init {
        require(value in 1..MAX) { "In-flight limit must be in 1..$MAX, got $value" }
    }

    fun constrainedBy(receiveMaximum: Int): InFlightLimit = InFlightLimit(minOf(value, receiveMaximum.coerceIn(1, MAX)))

    companion object {
        const val MAX = 65_535
        val ProtocolMaximum = InFlightLimit(MAX)
    }
}
