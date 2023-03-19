package com.ditchoom.mqtt3.controlpacket

import com.ditchoom.mqtt.controlpacket.IDisconnectNotification
import com.ditchoom.mqtt.controlpacket.format.fixed.DirectionOfFlow

/**
 * The Server MUST validate that reserved bits are set to zero and disconnect the Client if they are not zero
 * [MQTT-3.14.1-1].
 *
 * 3.14.4 Response
 *
 * After sending a DISCONNECT Packet the Client:
 *
 * MUST close the Network Connection [MQTT-3.14.4-1].
 *
 * MUST NOT send any more Control Packets on that Network Connection [MQTT-3.14.4-2].
 *
 * On receipt of DISCONNECT the Server:
 *
 * MUST discard any Will Message associated with the current connection without publishing it, as described in Section
 * 3.1.2.5 [MQTT-3.14.4-3]
 *
 * SHOULD close the Network Connection if the Client has not already done so.
 */
object DisconnectNotification :
    ControlPacketV4(14, DirectionOfFlow.BIDIRECTIONAL),
    IDisconnectNotification
