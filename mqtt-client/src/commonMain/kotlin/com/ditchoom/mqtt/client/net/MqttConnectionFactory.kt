package com.ditchoom.mqtt.client.net

import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.Payload
import com.ditchoom.buffer.flow.Connection
import com.ditchoom.mqtt.connection.MqttBroker
import com.ditchoom.mqtt.connection.MqttConnectionOptions
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.ControlPacketFactory

/**
 * Opens one connection for [connectionOp] and wraps it in an `MqttCodec`-typed [Connection] by
 * routing through [resolver] to the matching [MqttTransport]. Option-list iteration (HA failover)
 * and the CONNECT handshake live in [com.ditchoom.mqtt.client.ConnectivityManager]; this function is
 * intentionally atomic so the caller can count each attempt and decide retry/failover policy.
 *
 * Pass a custom [resolver] to plug in or re-route transports (e.g. QUIC → WebTransport on web). The
 * transports themselves — [TcpMqttTransport], [WebSocketMqttTransport], [QuicMqttTransport],
 * [WebTransportMqttTransport] — are the composable seam.
 */
suspend fun defaultSingleConnection(
    connectionOp: MqttConnectionOptions,
    factory: ControlPacketFactory,
    publishCodecForTopic: (topicName: String) -> Codec<out Payload>? = { null },
    resolver: MqttTransportResolver = DefaultMqttTransportResolver,
): Connection<ControlPacket> = resolver.resolve(connectionOp).connect(connectionOp, factory, publishCodecForTopic)

/**
 * Curries [defaultSingleConnection] against [broker]'s control-packet factory. The
 * `publishCodecForTopic` lookup is supplied at invocation time by
 * [com.ditchoom.mqtt.client.ConnectivityManager] from `MqttClient`'s
 * [com.ditchoom.mqtt.client.TopicCodecRegistry] — passing it through the call (rather than capturing
 * at construction) makes it impossible for a caller-supplied `connectSingle` to bypass the registry.
 */
fun defaultSingleConnection(
    broker: MqttBroker,
): suspend (MqttConnectionOptions, (topicName: String) -> Codec<out Payload>?) -> Connection<ControlPacket> =
    { op, publishCodecForTopic ->
        defaultSingleConnection(
            op,
            broker.connectionRequest.controlPacketFactory,
            publishCodecForTopic,
        )
    }
