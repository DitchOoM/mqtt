package com.ditchoom.mqtt.client.net

import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.Payload
import com.ditchoom.buffer.flow.Connection
import com.ditchoom.mqtt.connection.MqttConnectionOptions
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.ControlPacketFactory

/**
 * MQTT over QUIC (**experimental, non-standard** — mirrors EMQX's mapping). Not yet implemented.
 *
 * Design (single bidirectional stream), requiring `com.ditchoom:socket-quic-default` (+ the
 * `socket-quic-quiche` engine on non-JS targets) — dependencies are present-but-commented in
 * `mqtt-client/build.gradle.kts`:
 *
 * ```
 * val op = options as MqttConnectionOptions.QuicConnectionOptions
 * // withQuicConnection's block boundary IS the connection lifetime (there is no close()), so bridge
 * // it to Connection.close() by holding the block open on a coroutine tied to the connection's
 * // lifetime and completing a CompletableJob/Deferred when the caller closes.
 * return withQuicConnection(
 *     op.host, op.port,
 *     QuicOptions(alpnProtocols = op.alpnProtocols),
 *     buildTransportConfig(op),
 * ) {
 *     val stream = openStream()                         // QuicByteStream : ByteStream
 *     CodecConnection(stream, MqttCodec(factory, publishCodecForTopic), buildTransportConfig(op))
 * }
 * ```
 *
 * QUIC needs raw UDP, which browsers do not expose; on JS/wasmJs the QUIC engine throws. Use
 * [WebTransportMqttTransport] there (see [MqttConnectionOptions.WebTransportConnectionOptions]).
 */
object QuicMqttTransport : MqttTransport {
    override suspend fun connect(
        options: MqttConnectionOptions,
        factory: ControlPacketFactory,
        publishCodecForTopic: (topicName: String) -> Codec<out Payload>?,
    ): Connection<ControlPacket> =
        throw NotImplementedError(
            "QUIC MQTT transport is not yet implemented. See the single-bidirectional-stream design " +
                "in the QuicMqttTransport KDoc.",
        )
}
