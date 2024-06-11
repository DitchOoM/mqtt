package com.ditchoom.mqtt.controlpacket.format.fixed

/**
 * Direction of the control packet
 * @see https://docs.oasis-open.org/mqtt/mqtt/v5.0/cos02/mqtt-v5.0-cos02.html#_Toc1477322
 */
enum class DirectionOfFlow {
    FORBIDDEN,
    CLIENT_TO_SERVER,
    SERVER_TO_CLIENT,
    BIDIRECTIONAL, // Client to Server or Server to Client
}
