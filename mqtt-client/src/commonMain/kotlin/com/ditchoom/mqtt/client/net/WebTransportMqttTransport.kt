package com.ditchoom.mqtt.client.net

import com.ditchoom.buffer.codec.Codec
import com.ditchoom.buffer.codec.Payload
import com.ditchoom.buffer.flow.Connection
import com.ditchoom.mqtt.connection.MqttConnectionOptions
import com.ditchoom.mqtt.controlpacket.ControlPacket
import com.ditchoom.mqtt.controlpacket.ControlPacketFactory

/**
 * MQTT over WebTransport (**experimental**, no standard mapping exists). Not yet implemented.
 *
 * WebTransport rides HTTP/3 (QUIC) and is available on **every** target including the browser, so it
 * is the web substitute for QUIC where raw UDP is unavailable. Requires
 * `com.ditchoom:socket-webtransport` — dependency is present-but-commented in
 * `mqtt-client/build.gradle.kts`.
 *
 * Design (single bidirectional stream):
 *
 * ```
 * val op = options as MqttConnectionOptions.WebTransportConnectionOptions
 * val session = webTransportSupport().connect(op.buildUrl())   // real on all targets incl. browser
 * val stream = session.openBidiStream()                        // ByteStream
 * // Keep `session` alive for the connection's lifetime and close it from Connection.close().
 * return CodecConnection(stream, MqttCodec(factory, publishCodecForTopic), buildTransportConfig(op))
 * ```
 */
object WebTransportMqttTransport : MqttTransport {
    override suspend fun connect(
        options: MqttConnectionOptions,
        factory: ControlPacketFactory,
        publishCodecForTopic: (topicName: String) -> Codec<out Payload>?,
    ): Connection<ControlPacket> =
        throw NotImplementedError(
            "WebTransport MQTT transport is not yet implemented. See the single-bidirectional-stream " +
                "design in the WebTransportMqttTransport KDoc.",
        )
}
