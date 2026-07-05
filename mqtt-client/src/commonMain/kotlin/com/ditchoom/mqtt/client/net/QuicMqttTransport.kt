package com.ditchoom.mqtt.client.net

import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.Payload
import com.ditchoom.buffer.flow.Connection
import com.ditchoom.mqtt.client.MqttCodec
import com.ditchoom.mqtt.connection.MqttConnectionOptions
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.ControlPacketFactory
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicTransport
import com.ditchoom.socket.transport.CodecConnection

/**
 * MQTT over QUIC (**experimental, non-standard** — mirrors EMQX's mapping). The whole MQTT byte
 * stream is tunneled over a single bidirectional QUIC stream, so [MqttCodec]'s framing is reused
 * unchanged.
 *
 * [QuicTransport] establishes the QUIC connection, opens one bidi stream, and wraps it in a
 * session-owning `ByteStream`, so `CodecConnection.close()` tears down the entire QUIC connection —
 * no extra lifecycle plumbing. ALPN is `"mqtt"` by default (see
 * [MqttConnectionOptions.QuicConnectionOptions.alpnProtocols]).
 *
 * **Native only:** the default QUIC engine throws [UnsupportedOperationException] on JS/wasmJs and
 * tvOS/watchOS (no raw UDP / no engine). Use [WebTransportMqttTransport] on the web.
 *
 * > Not integration-tested against a broker — MQTT-over-QUIC has no IANA-standard binding.
 */
object QuicMqttTransport : MqttTransport {
    override suspend fun connect(
        options: MqttConnectionOptions,
        factory: ControlPacketFactory,
        publishCodecForTopic: (topicName: String) -> Codec<out Payload>?,
    ): Connection<ControlPacket> {
        val op = options as MqttConnectionOptions.QuicConnectionOptions
        return CodecConnection.connect(
            op.host,
            op.port,
            MqttCodec(factory, publishCodecForTopic),
            QuicTransport(
                QuicOptions(
                    alpnProtocols = op.alpnProtocols,
                    verifyPeer = op.tlsVerifyCerts,
                ),
            ),
            buildTransportConfig(op),
        )
    }
}
