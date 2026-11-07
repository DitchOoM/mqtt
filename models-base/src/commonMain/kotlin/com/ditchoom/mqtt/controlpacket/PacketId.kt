package com.ditchoom.mqtt.controlpacket

import kotlin.jvm.JvmInline

/** A non-zero MQTT packet identifier (§2.2.1): QoS 1/2 PUBLISH, PUBREL, SUBSCRIBE, UNSUBSCRIBE. */
@JvmInline
value class PacketId(
    val value: Int,
) {
    init {
        require(value in MIN..MAX) { "Packet identifier must be in $MIN..$MAX, got $value" }
    }

    fun next(): PacketId = PacketId(if (value == MAX) MIN else value + 1)

    /** The first identifier after this one, in wrap-around order, that [isInUse] does not claim. */
    inline fun nextFree(isInUse: (PacketId) -> Boolean): PacketIdAllocation {
        var candidate = next()
        repeat(MAX) {
            if (!isInUse(candidate)) return PacketIdAllocation.Allocated(candidate)
            candidate = candidate.next()
        }
        return PacketIdAllocation.Exhausted
    }

    companion object {
        const val MIN = 1
        const val MAX = 65_535

        /** Validates an identifier read off the wire, where out-of-range values are possible. */
        fun parse(value: Int): PacketIdParse =
            if (value in
                MIN..MAX
            ) {
                PacketIdParse.Valid(PacketId(value))
            } else {
                PacketIdParse.OutOfRange(value)
            }
    }
}

sealed interface PacketIdParse {
    data class Valid(
        val id: PacketId,
    ) : PacketIdParse

    data class OutOfRange(
        val value: Int,
    ) : PacketIdParse
}

sealed interface PacketIdAllocation {
    data class Allocated(
        val id: PacketId,
    ) : PacketIdAllocation

    /** Every identifier is held by an unacknowledged packet. */
    data object Exhausted : PacketIdAllocation
}

/** Thrown by a [com.ditchoom.mqtt.Persistence] when all 65 535 identifiers are in flight. */
class PacketIdsExhaustedException : IllegalStateException()
