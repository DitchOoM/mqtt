package com.ditchoom.mqtt.client

import com.ditchoom.mqtt.controlpacket.ControlPacket
import kotlin.time.Duration

interface Observer {
    fun incomingPacket(
        brokerId: Int,
        protocolVersion: Byte,
        packet: ControlPacket,
    )

    fun wrotePackets(
        brokerId: Int,
        protocolVersion: Byte,
        controlPackets: Collection<ControlPacket>,
    )

    fun shutdown(
        brokerId: Int,
        protocolVersion: Byte,
    )

    // Ping timer
    fun resetPingTimer(
        brokerId: Int,
        protocolVersion: Byte,
    )

    fun sendingPing(
        brokerId: Int,
        protocolVersion: Byte,
    )

    fun delayPing(
        brokerId: Int,
        protocolVersion: Byte,
        delayDuration: Duration,
    )

    fun cancelPingTimer(
        brokerId: Int,
        protocolVersion: Byte,
    )

    // Reconnection
    fun stopReconnecting(
        brokerId: Int,
        protocolVersion: Byte,
        cause: Throwable?,
    )

    fun reconnectIn(
        brokerId: Int,
        protocolVersion: Byte,
        delay: Duration,
    )
}
