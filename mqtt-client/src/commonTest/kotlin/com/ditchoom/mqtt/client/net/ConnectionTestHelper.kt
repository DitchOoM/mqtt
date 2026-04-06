package com.ditchoom.mqtt.client.net

import com.ditchoom.buffer.flow.Connection
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

/**
 * Creates a [Connection] factory for integration tests.
 *
 * Iterates the broker's connection options, trying each endpoint until one succeeds.
 * TCP connections use [CodecConnection] with [MqttCodec] and [TcpTransport].
 */
fun createConnectFactory(broker: MqttBroker): suspend () -> Connection<ControlPacket> =
    createConnectFactory(broker.connectionOps, broker.connectionRequest.controlPacketFactory)

fun createConnectFactory(
    connectionOps: Collection<MqttConnectionOptions>,
    factory: ControlPacketFactory,
): suspend () -> Connection<ControlPacket> =
    {
        var lastException: Throwable? = null
        var result: Connection<ControlPacket>? = null
        for (connectionOp in connectionOps) {
            try {
                result = connectSingle(connectionOp, factory)
                break
            } catch (e: Throwable) {
                lastException = e
            }
        }
        result ?: throw lastException ?: IllegalStateException("No connection options configured")
    }

private suspend fun connectSingle(
    connectionOp: MqttConnectionOptions,
    factory: ControlPacketFactory,
): Connection<ControlPacket> =
    when (connectionOp) {
        is MqttConnectionOptions.SocketConnection -> {
            val socketOptions =
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
            CodecConnection.connect(
                connectionOp.host,
                connectionOp.port,
                MqttCodec(factory),
                TcpTransport(),
                ConnectionOptions(
                    socketOptions = socketOptions,
                    connectionTimeout = connectionOp.connectionTimeout,
                    readTimeout = connectionOp.readTimeout,
                    writeTimeout = connectionOp.writeTimeout,
                ),
            )
        }

        is MqttConnectionOptions.WebSocketConnectionOptions -> {
            throw UnsupportedOperationException(
                "WebSocket transport not yet supported in tests.",
            )
        }
    }
