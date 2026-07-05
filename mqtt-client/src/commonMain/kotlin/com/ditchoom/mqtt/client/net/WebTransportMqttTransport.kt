package com.ditchoom.mqtt.client.net

import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.Payload
import com.ditchoom.buffer.flow.Connection
import com.ditchoom.mqtt.client.MqttCodec
import com.ditchoom.mqtt.connection.MqttConnectionOptions
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.ControlPacketFactory
import com.ditchoom.socket.transport.CodecConnection
import com.ditchoom.socket.webtransport.WebTransportTransport

/**
 * MQTT over WebTransport (**experimental**, no standard mapping exists). The MQTT byte stream is
 * tunneled over a single bidirectional WebTransport stream, reusing [MqttCodec]'s framing.
 *
 * WebTransport rides HTTP/3 (QUIC) and is available on **every** target including the browser, so it
 * is the web substitute for QUIC where raw UDP (and therefore [QuicMqttTransport]) is unavailable.
 * [WebTransportTransport] opens the session + one bidi stream and wraps it session-owning, so
 * `CodecConnection.close()` closes the whole session — no extra lifecycle plumbing. The connection
 * URL is `https://host:port/endpoint` (see
 * [MqttConnectionOptions.WebTransportConnectionOptions.endpoint]).
 *
 * > Not integration-tested against a broker — MQTT-over-WebTransport is unspecified.
 */
object WebTransportMqttTransport : MqttTransport {
    override suspend fun connect(
        options: MqttConnectionOptions,
        factory: ControlPacketFactory,
        publishCodecForTopic: (topicName: String) -> Codec<out Payload>?,
    ): Connection<ControlPacket> {
        val op = options as MqttConnectionOptions.WebTransportConnectionOptions
        return CodecConnection.connect(
            op.host,
            op.port,
            MqttCodec(factory, publishCodecForTopic),
            WebTransportTransport(path = op.endpoint),
            buildTransportConfig(op),
        )
    }
}
