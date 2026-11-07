---
title: Transports
---

# Transports

The client dials whatever socket `Transport` you put in an
[`MqttEndpoint`](../core-concepts/connection-options.md). A `Transport` turns
`(host, port, TransportConfig)` into a byte stream, and the client frames MQTT packets on that stream
the same way whatever carries it. Anything that implements `com.ditchoom.socket.transport.Transport`
works, including transports you write yourself.

| Transport | Class | Artifact | Status |
|-----------|-------|----------|--------|
| TCP, TCP + TLS | `com.ditchoom.socket.transport.TcpTransport` | `com.ditchoom:socket` (included) | Supported |
| WebSocket (`ws` / `wss`) | `com.ditchoom.websocket.tcp.WebSocketTransport` | `com.ditchoom:websocket-tcp` | Supported |
| QUIC | `com.ditchoom.socket.quic.QuicTransport` | `com.ditchoom:socket-quic-default` | Experimental |
| WebTransport | `com.ditchoom.socket.webtransport.WebTransportTransport` | `com.ditchoom:socket-webtransport` | Experimental |
| Fallback chain | `com.ditchoom.socket.transport.FallbackTransport` | `com.ditchoom:socket` (included) | Supported |

## Dependencies

`mqtt-client` exposes `com.ditchoom:socket` as an `api` dependency, so `TcpTransport`,
`FallbackTransport`, `TransportConfig` and `TlsConfig` are on your classpath already. It does not bring
any other transport. Add the artifact for each transport you use:

```kotlin
dependencies {
    implementation("com.ditchoom:mqtt-client:3.0.0")
    implementation("com.ditchoom:mqtt-5-models:3.0.0")

    implementation("com.ditchoom:websocket-tcp:2.1.1")           // WebSocket
    implementation("com.ditchoom:socket-quic-default:4.26.0")    // QUIC
    implementation("com.ditchoom:socket-webtransport:4.26.0")    // WebTransport
}
```

Keep the socket transport artifacts on the same version as the `socket` that `mqtt-client` brings
(4.26.0 for mqtt 3.0.0).

## TCP and TLS

`TcpTransport()` is the default, so a plain endpoint needs only a host and port. TLS comes from
`config.tls`:

```kotlin
import com.ditchoom.mqtt.client.MqttEndpoint
import com.ditchoom.socket.TransportConfig

val tcp = MqttEndpoint("broker.example.com", 1883)
val tls = MqttEndpoint("broker.example.com", 8883, config = TransportConfig.tlsDefault())
```

See [Connection Options](../core-concepts/connection-options.md#tls) for custom `TlsConfig` rules.

## WebSocket

`WebSocketTransport` runs the HTTP upgrade and carries MQTT packets in binary frames. Brokers expect
the `mqtt` subprotocol and usually serve it on a path such as `/mqtt`. TLS in the config gives `wss`.

```kotlin
import com.ditchoom.mqtt.client.MqttEndpoint
import com.ditchoom.socket.TransportConfig
import com.ditchoom.websocket.tcp.WebSocketTransport

val ws =
    MqttEndpoint(
        host = "broker.example.com",
        port = 8083,
        transport = WebSocketTransport(websocketEndpoint = "/mqtt", protocols = listOf("mqtt")),
    )

val wss =
    MqttEndpoint(
        host = "broker.example.com",
        port = 443,
        transport = WebSocketTransport(websocketEndpoint = "/mqtt", protocols = listOf("mqtt")),
        config = TransportConfig.tlsDefault(),
    )
```

`WebSocketTransport` also takes `requestCompression` and `compressionOptions` for permessage-deflate,
and `underlying` (default `TcpTransport()`) for the stream it upgrades.

`websocket-tcp` builds the WebSocket on a TCP socket, so it is available where `TcpTransport` is: JVM,
Android, Node.js, Apple and Linux x64. It is not available in the browser.

## QUIC (experimental)

`QuicTransport` opens a QUIC connection and carries MQTT on one bidirectional stream. MQTT over QUIC
is not standardized, but EMQX implements it. The ALPN must be `"mqtt"`.

```kotlin
import com.ditchoom.mqtt.client.MqttEndpoint
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicTransport

val quic =
    MqttEndpoint(
        host = "broker.example.com",
        port = 14567,
        transport = QuicTransport(QuicOptions(alpnProtocols = listOf("mqtt"), persistentStreams = true)),
    )
```

- QUIC is always encrypted. Certificate checks are set on `QuicOptions` (`verifyPeer`,
  `trustedCaCertificatesPem`), not on `TransportConfig.tls`.
- Set `persistentStreams = true`. A QUIC stream takes its read deadline from `QuicOptions`, not from
  the endpoint's `TransportConfig`, and the default bounds each read to 15 seconds, which ends an idle
  MQTT session.
- `QuicOptions.idleTimeout` defaults to 30 seconds. Keep the MQTT `keepAliveSeconds` below it, or set
  `QuicOptions.keepAliveInterval`, so an idle session is not closed by the QUIC idle timer.
- Native only. JS and wasm have no raw UDP, and the default engine has no tvOS or watchOS
  implementation; on those targets `QuicTransport` throws `UnsupportedOperationException`.

## WebTransport (experimental)

`WebTransportTransport` opens a WebTransport session at `https://host:port/path` and carries MQTT on
one bidirectional stream. It works in the browser, where it uses the browser's own `WebTransport`
object. There is no MQTT-over-WebTransport specification, so the broker or a gateway in front of it
must accept MQTT bytes on that stream.

```kotlin
import com.ditchoom.mqtt.client.MqttEndpoint
import com.ditchoom.socket.webtransport.WebTransportTransport

val webTransport =
    MqttEndpoint(
        host = "broker.example.com",
        port = 443,
        transport = WebTransportTransport(path = "/mqtt"),
    )
```

## FallbackTransport

`FallbackTransport` tries a chain of transports against one host and port and remembers which one
worked. Use it when the same address serves several transports, for example QUIC on UDP 443 and
`wss` on TCP 443:

```kotlin
import com.ditchoom.mqtt.client.MqttEndpoint
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.quic.QuicOptions
import com.ditchoom.socket.quic.QuicTransport
import com.ditchoom.socket.transport.FallbackTransport
import com.ditchoom.websocket.tcp.WebSocketTransport

val endpoint =
    MqttEndpoint(
        host = "broker.example.com",
        port = 443,
        transport =
            FallbackTransport(
                listOf(
                    QuicTransport(QuicOptions(alpnProtocols = listOf("mqtt"), persistentStreams = true)),
                    WebSocketTransport(websocketEndpoint = "/mqtt", protocols = listOf("mqtt")),
                ),
            ),
        config = TransportConfig.tlsDefault(),
    )
```

Every transport in the chain gets the same host, port and config. When the transports live on
different ports or hosts, use separate endpoints and [failover](#high-availability-failover) instead.

## Custom transports

Implement `Transport` to plug in your own byte stream: a proxy, a tunnel, instrumentation, or a test
double.

```kotlin
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.transport.TcpTransport
import com.ditchoom.socket.transport.Transport

class LoggingTransport(
    private val delegate: Transport = TcpTransport(),
) : Transport {
    override suspend fun connect(
        hostname: String,
        port: Int,
        config: TransportConfig,
    ): ByteStream {
        println("Dialing $hostname:$port")
        return delegate.connect(hostname, port, config)
    }
}

val endpoint = MqttEndpoint("broker.example.com", 1883, transport = LoggingTransport())
```

In tests, `MemoryTransport.createPair()` returns two connected in-memory `ByteStream`s. A custom
`Transport` can return one end to the client while the test drives the other end as a scripted broker.

## High availability failover

`MqttClient.start` takes one `endpoint` and an ordered `failover` list. Each connection pass dials
`endpoint` first, then each failover endpoint in order, and stops at the first CONNACK. Endpoints can
use different hosts, ports and transports:

```kotlin
val client =
    MqttClient.start(
        scope = scope,
        broker = broker,
        persistence = persistence,
        endpoint = MqttEndpoint("primary.example.com", 8883, config = TransportConfig.tlsDefault()),
        failover =
            listOf(
                MqttEndpoint("secondary.example.com", 8883, config = TransportConfig.tlsDefault()),
                MqttEndpoint(
                    host = "secondary.example.com",
                    port = 443,
                    transport = WebSocketTransport(websocketEndpoint = "/mqtt", protocols = listOf("mqtt")),
                    config = TransportConfig.tlsDefault(),
                ),
            ),
    )
```

See [Reconnection & High Availability](./reconnection-and-high-availability.md) for how passes repeat
after a connection drops.
