package com.ditchoom.mqtt.client.net

import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.Payload
import com.ditchoom.buffer.flow.Connection
import com.ditchoom.buffer.flow.mapNotNull
import com.ditchoom.mqtt.client.MqttCodec
import com.ditchoom.mqtt.connection.MqttConnectionOptions
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.ControlPacketFactory
import com.ditchoom.socket.transport.TcpTransport
import com.ditchoom.websocket.WebSocketMessage
import com.ditchoom.websocket.connectWebSocket
import com.ditchoom.websocket.WebSocketConnectionOptions as WsLibOptions

/**
 * MQTT over WebSocket. Opens a raw TCP [com.ditchoom.buffer.flow.ByteStream], layers the WebSocket
 * protocol on top (HTTP upgrade + permessage-deflate negotiation) with [MqttCodec] as the binary
 * frame codec, then maps the resulting `WebSocketMessage<ControlPacket>` connection down to a
 * transport-neutral `Connection<ControlPacket>` — MQTT packets ride in binary frames.
 */
object WebSocketMqttTransport : MqttTransport {
    override suspend fun connect(
        options: MqttConnectionOptions,
        factory: ControlPacketFactory,
        publishCodecForTopic: (topicName: String) -> Codec<out Payload>?,
    ): Connection<ControlPacket> {
        val op = options as MqttConnectionOptions.WebSocketConnectionOptions
        val byteStream = TcpTransport().connect(op.host, op.port, buildTransportConfig(op))
        val wsConnection: Connection<WebSocketMessage<ControlPacket>> =
            connectWebSocket(
                transport = byteStream,
                connectionOptions =
                    WsLibOptions(
                        name = op.host,
                        port = op.port,
                        tls = op.tlsEnabled,
                        connectionTimeout = op.connectionTimeout,
                        readTimeout = op.readTimeout,
                        writeTimeout = op.writeTimeout,
                        websocketEndpoint = op.websocketEndpoint,
                        protocols = op.protocols,
                    ),
                binaryCodec = MqttCodec(factory, publishCodecForTopic),
            )
        return wsConnection.mapNotNull(
            encode = { packet -> WebSocketMessage.Binary(packet) },
            decode = { message ->
                when (message) {
                    is WebSocketMessage.Binary -> message.payload
                    else -> null
                }
            },
        )
    }
}
