package com.ditchoom.mqtt.client.net

import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.Payload
import com.ditchoom.buffer.flow.Connection
import com.ditchoom.buffer.flow.mapNotNull
import com.ditchoom.mqtt.client.MqttCodec
import com.ditchoom.mqtt.connection.MqttBroker
import com.ditchoom.mqtt.connection.MqttConnectionOptions
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.ControlPacketFactory
import com.ditchoom.socket.ConnectionOptions
import com.ditchoom.socket.SocketOptions
import com.ditchoom.socket.TlsConfig
import com.ditchoom.socket.transport.CodecConnection
import com.ditchoom.socket.transport.TcpTransport
import com.ditchoom.websocket.WebSocketMessage
import com.ditchoom.websocket.connectWebSocket
import com.ditchoom.websocket.WebSocketConnectionOptions as WsLibOptions

/**
 * Opens one TCP or WebSocket connection for the given [connectionOp] and wraps it in an
 * `MqttCodec`-typed [Connection]. Option-list iteration (HA failover) and handshake live in
 * [com.ditchoom.mqtt.client.ConnectivityManager]; this function is intentionally atomic so the
 * caller can count each attempt and decide retry/failover policy.
 */
suspend fun defaultSingleConnection(
    connectionOp: MqttConnectionOptions,
    factory: ControlPacketFactory,
    publishCodecForTopic: (topicName: String) -> Codec<out Payload>? = { null },
): Connection<ControlPacket> =
    when (connectionOp) {
        is MqttConnectionOptions.SocketConnection -> {
            CodecConnection.connect(
                connectionOp.host,
                connectionOp.port,
                MqttCodec(factory, publishCodecForTopic),
                TcpTransport(),
                ConnectionOptions(
                    socketOptions = buildSocketOptions(connectionOp),
                    connectionTimeout = connectionOp.connectionTimeout,
                    readTimeout = connectionOp.readTimeout,
                    writeTimeout = connectionOp.writeTimeout,
                ),
            )
        }

        is MqttConnectionOptions.WebSocketConnectionOptions -> {
            val byteStream =
                TcpTransport().connect(
                    connectionOp.host,
                    connectionOp.port,
                    ConnectionOptions(
                        socketOptions = buildSocketOptions(connectionOp),
                        connectionTimeout = connectionOp.connectionTimeout,
                        readTimeout = connectionOp.readTimeout,
                        writeTimeout = connectionOp.writeTimeout,
                    ),
                )
            val wsConnection: Connection<WebSocketMessage<ControlPacket>> =
                connectWebSocket(
                    transport = byteStream,
                    connectionOptions =
                        WsLibOptions(
                            name = connectionOp.host,
                            port = connectionOp.port,
                            tls = connectionOp.tlsEnabled,
                            connectionTimeout = connectionOp.connectionTimeout,
                            readTimeout = connectionOp.readTimeout,
                            writeTimeout = connectionOp.writeTimeout,
                            websocketEndpoint = connectionOp.websocketEndpoint,
                            protocols = connectionOp.protocols,
                        ),
                    binaryCodec = MqttCodec(factory, publishCodecForTopic),
                )
            wsConnection.mapNotNull(
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

/**
 * Curries [defaultSingleConnection] against [broker]'s control-packet factory. The
 * `publishCodecForTopic` lookup is supplied at invocation time by
 * [com.ditchoom.mqtt.client.ConnectivityManager] from `MqttClient`'s
 * [com.ditchoom.mqtt.client.TopicCodecRegistry] — passing it through the call
 * (rather than capturing at construction) makes it impossible for a caller-
 * supplied `connectSingle` to bypass the registry.
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

private fun buildSocketOptions(connectionOp: MqttConnectionOptions): SocketOptions =
    if (connectionOp.tlsEnabled) {
        SocketOptions(
            tls =
                TlsConfig(
                    verifyCertificates = connectionOp.tlsVerifyCerts,
                    verifyHostname = connectionOp.tlsVerifyHostname,
                    allowExpiredCertificates = connectionOp.tlsAllowExpired,
                    allowSelfSigned = connectionOp.tlsAllowSelfSigned,
                ),
        )
    } else {
        SocketOptions()
    }
