package com.ditchoom.mqtt.client.net

import com.ditchoom.buffer.flow.Connection
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.mqtt.client.MqttCodec
import com.ditchoom.mqtt.client.MqttEndpoint
import com.ditchoom.mqtt.client.TopicCodec
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.ControlPacketFactory
import com.ditchoom.socket.transport.CodecConnection
import com.ditchoom.socket.transport.OverflowPolicy
import kotlinx.coroutines.CoroutineScope

/**
 * Opens exactly one connection to one [MqttEndpoint]. Failover across endpoints and the CONNECT
 * handshake live in [com.ditchoom.mqtt.client.ConnectivityManager], which counts each attempt.
 *
 * The client always dials through [EndpointConnector]; tests substitute a scripted connector to
 * drive the client packet by packet.
 */
internal fun interface MqttConnector {
    /**
     * [scope] owns the connection's writer coroutine, so the writer lives exactly as long as the
     * client that owns the connection. [codecForTopic] resolves the payload codec of every
     * incoming PUBLISH while its frame is still being decoded.
     */
    suspend fun connect(
        scope: CoroutineScope,
        endpoint: MqttEndpoint,
        factory: ControlPacketFactory,
        codecForTopic: (topicName: String) -> TopicCodec,
    ): Connection<ControlPacket>
}

/**
 * Depth of a connection's outbound hand-off queue, used with [OverflowPolicy.Suspend] so no control
 * packet is ever dropped. The client's single writer already bounds what reaches it (QoS 1/2 by the
 * send quota), so this only absorbs bursts of QoS 0 and acknowledgements; a full queue suspends the
 * writer, which is the back-pressure an at-least-once protocol wants.
 */
internal const val MQTT_OUTBOUND_QUEUE_CAPACITY: Int = 64

/** Dials [MqttEndpoint.transport] and frames the resulting byte stream with [MqttCodec]. */
internal object EndpointConnector : MqttConnector {
    override suspend fun connect(
        scope: CoroutineScope,
        endpoint: MqttEndpoint,
        factory: ControlPacketFactory,
        codecForTopic: (topicName: String) -> TopicCodec,
    ): Connection<ControlPacket> =
        CodecConnection.connect(
            hostname = endpoint.host,
            port = endpoint.port,
            codec = MqttCodec(factory, codecForTopic),
            scope = scope,
            outboundCapacity = MQTT_OUTBOUND_QUEUE_CAPACITY,
            overflowPolicy = OverflowPolicy.Suspend,
            transport = endpoint.transport,
            config = endpoint.config.copy(readPolicy = ReadPolicy.UntilClosed),
        )
}
