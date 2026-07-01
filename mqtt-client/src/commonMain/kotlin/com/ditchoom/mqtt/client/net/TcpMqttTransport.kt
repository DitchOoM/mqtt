package com.ditchoom.mqtt.client.net

import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.Payload
import com.ditchoom.buffer.flow.Connection
import com.ditchoom.buffer.flow.ReadPolicy
import com.ditchoom.buffer.flow.WritePolicy
import com.ditchoom.mqtt.client.MqttCodec
import com.ditchoom.mqtt.connection.MqttConnectionOptions
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.ControlPacketFactory
import com.ditchoom.socket.TlsConfig
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.transport.CodecConnection
import com.ditchoom.socket.transport.TcpTransport

/**
 * Plain TCP (optionally TLS) MQTT transport. `ClientSocket` is itself a buffer-flow `ByteStream`, so
 * [CodecConnection] frames it with [MqttCodec] directly — no adapter.
 */
object TcpMqttTransport : MqttTransport {
    override suspend fun connect(
        options: MqttConnectionOptions,
        factory: ControlPacketFactory,
        publishCodecForTopic: (topicName: String) -> Codec<out Payload>?,
    ): Connection<ControlPacket> =
        CodecConnection.connect(
            options.host,
            options.port,
            MqttCodec(factory, publishCodecForTopic),
            TcpTransport(),
            buildTransportConfig(options),
        )
}

/**
 * Maps [MqttConnectionOptions] onto socket's single, immutable [TransportConfig] (which folds
 * together the old `ConnectionOptions` + `SocketOptions` + the per-call `read(timeout)` default).
 *
 * `readPolicy = ReadPolicy.UntilClosed`: an MQTT session is a long-lived stream that sits idle
 * between messages, so a per-read deadline would tear it down on the first quiet period. Liveness is
 * the keepalive/PINGREQ's job; the CONNECT handshake is bounded separately in
 * [com.ditchoom.mqtt.client.ConnectivityManager]. `writePolicy` stays [WritePolicy.Bounded] on the
 * configured write timeout, and `connectTimeout` bounds the TCP/TLS handshake itself.
 */
internal fun buildTransportConfig(op: MqttConnectionOptions): TransportConfig =
    TransportConfig(
        readPolicy = ReadPolicy.UntilClosed,
        writePolicy = WritePolicy.Bounded(op.writeTimeout),
        connectTimeout = op.connectionTimeout,
        tls =
            if (op.tlsEnabled) {
                TlsConfig(
                    verifyCertificates = op.tlsVerifyCerts,
                    verifyHostname = op.tlsVerifyHostname,
                    allowExpiredCertificates = op.tlsAllowExpired,
                    allowSelfSigned = op.tlsAllowSelfSigned,
                )
            } else {
                null
            },
    )
