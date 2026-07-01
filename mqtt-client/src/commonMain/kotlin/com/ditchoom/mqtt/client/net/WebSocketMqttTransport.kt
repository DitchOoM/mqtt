package com.ditchoom.mqtt.client.net

import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.Payload
import com.ditchoom.buffer.flow.Connection
import com.ditchoom.mqtt.connection.MqttConnectionOptions
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.ControlPacketFactory

/**
 * MQTT over WebSocket.
 *
 * **Temporarily disabled.** The `com.ditchoom:websocket` library is still built against buffer 4 /
 * socket 3.0.1 and cannot coexist with buffer 6 + socket 3.6.x, so its dependency has been removed
 * from `mqtt-client/build.gradle.kts` for now. Re-enabling is mechanical once a buffer-6 build of
 * `websocket` is published (tracked in TODO.md):
 *   1. Restore `implementation(libs.websocket)` in `mqtt-client/build.gradle.kts` (common + test).
 *   2. Replace the body below with the preserved wiring, adjusting to the migrated websocket API.
 *
 * Preserved wiring (open a TCP `ByteStream` via the new socket API, layer WebSocket on top, then map
 * the `WebSocketMessage<ControlPacket>` connection down to a `Connection<ControlPacket>`):
 *
 * ```
 * val op = options as MqttConnectionOptions.WebSocketConnectionOptions
 * val byteStream = TcpTransport().connect(op.host, op.port, buildTransportConfig(op))
 * val wsConnection: Connection<WebSocketMessage<ControlPacket>> =
 *     connectWebSocket(
 *         transport = byteStream,
 *         connectionOptions = WsLibOptions(
 *             name = op.host,
 *             port = op.port,
 *             tls = op.tlsEnabled,
 *             connectionTimeout = op.connectionTimeout,
 *             readTimeout = op.readTimeout,
 *             writeTimeout = op.writeTimeout,
 *             websocketEndpoint = op.websocketEndpoint,
 *             protocols = op.protocols,
 *         ),
 *         binaryCodec = MqttCodec(factory, publishCodecForTopic),
 *     )
 * return wsConnection.mapNotNull(
 *     encode = { packet -> WebSocketMessage.Binary(packet) },
 *     decode = { message -> (message as? WebSocketMessage.Binary)?.payload },
 * )
 * ```
 */
object WebSocketMqttTransport : MqttTransport {
    override suspend fun connect(
        options: MqttConnectionOptions,
        factory: ControlPacketFactory,
        publishCodecForTopic: (topicName: String) -> Codec<out Payload>?,
    ): Connection<ControlPacket> =
        throw UnsupportedOperationException(
            "WebSocket transport is temporarily disabled pending the websocket library's migration " +
                "to buffer 6 / socket 3.6.x. Restore the websocket dependency and the wiring preserved " +
                "in WebSocketMqttTransport to re-enable. See TODO.md.",
        )
}
