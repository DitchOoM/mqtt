package com.ditchoom.mqtt.client.net

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
): Connection<ControlPacket> =
    when (connectionOp) {
        is MqttConnectionOptions.SocketConnection -> {
            CodecConnection.connect(
                connectionOp.host,
                connectionOp.port,
                MqttCodec(factory),
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
                    binaryCodec = MqttCodec(factory),
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

/** Curries [defaultSingleConnection] against [broker]'s control-packet factory. */
fun defaultSingleConnection(broker: MqttBroker): suspend (MqttConnectionOptions) -> Connection<ControlPacket> =
    { op -> defaultSingleConnection(op, broker.connectionRequest.controlPacketFactory) }

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
