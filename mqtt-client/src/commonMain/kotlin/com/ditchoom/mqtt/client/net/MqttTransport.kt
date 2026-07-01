package com.ditchoom.mqtt.client.net

import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.Payload
import com.ditchoom.buffer.flow.Connection
import com.ditchoom.mqtt.connection.MqttConnectionOptions
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.ControlPacketFactory

/**
 * Opens exactly ONE transport connection for [options] and returns it as an `MqttCodec`-typed
 * [Connection]. Failover across a broker's option list and the MQTT CONNECT handshake live in
 * [com.ditchoom.mqtt.client.ConnectivityManager]; an [MqttTransport] is intentionally single-shot so
 * the caller can count each attempt and drive its own retry/failover policy.
 *
 * This is the composable transport seam. TCP ([TcpMqttTransport]), WebSocket
 * ([WebSocketMqttTransport]), QUIC ([QuicMqttTransport]) and WebTransport
 * ([WebTransportMqttTransport]) are each an [MqttTransport], and [MqttTransportResolver] maps an
 * [MqttConnectionOptions] subtype to the right one. Because every transport ultimately produces a
 * buffer-flow [Connection] framed by the same [com.ditchoom.mqtt.client.MqttCodec], the rest of the
 * client (`ConnectivityManager`, `ControlPacketProcessor`, `MqttClient`) is transport-agnostic.
 *
 * To plug in or swap transports, supply a custom [MqttTransportResolver] to
 * [defaultSingleConnection], or a custom `connectSingle` to `MqttClient.start`. For example, route
 * [MqttConnectionOptions.QuicConnectionOptions] to [WebTransportMqttTransport] on web targets, where
 * raw UDP (and therefore QUIC) is unavailable.
 */
fun interface MqttTransport {
    suspend fun connect(
        options: MqttConnectionOptions,
        factory: ControlPacketFactory,
        publishCodecForTopic: (topicName: String) -> Codec<out Payload>?,
    ): Connection<ControlPacket>
}
