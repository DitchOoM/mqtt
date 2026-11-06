---
title: Migration
---

# Migration

## 2.x to 3.0.0

3.0.0 replaces the MQTT-specific connection options and transport seam with socket's `Transport`. You
describe how to reach a broker with an [`MqttEndpoint`](./core-concepts/connection-options.md) and pass
it to `MqttClient.start`. Any socket `Transport` works: TCP, TLS, WebSocket, QUIC, WebTransport,
`FallbackTransport`, or your own. See [Transports](./recipes/transports.md).

### Removed and replaced APIs

| 2.x | 3.0.0 |
|-----|-------|
| `MqttConnectionOptions` | `com.ditchoom.mqtt.client.MqttEndpoint` |
| `MqttConnectionOptions.SocketConnection(host, port)` | `MqttEndpoint(host, port)` |
| `tlsEnabled = true` | `config = TransportConfig.tlsDefault()` |
| `tlsVerifyCerts`, `tlsVerifyHostname`, `tlsAllowExpired`, `tlsAllowSelfSigned` | `TlsConfig(verifyCertificates, verifyHostname, allowExpiredCertificates, allowSelfSigned)` in `config.tls` |
| `connectionTimeout` | `config.connectTimeout` (transport handshake) and `MqttEndpoint.connackTimeout` (CONNACK wait) |
| `readTimeout`, `writeTimeout` | None. Reads always use `ReadPolicy.UntilClosed`; writes follow `config.writePolicy`. |
| `MqttConnectionOptions.WebSocketConnectionOptions` | `MqttEndpoint(transport = WebSocketTransport(...))` from `com.ditchoom:websocket-tcp` |
| `MqttConnectionOptions.QuicConnectionOptions` | `MqttEndpoint(transport = QuicTransport(QuicOptions(...)))` from `com.ditchoom:socket-quic-default` |
| `MqttConnectionOptions.WebTransportConnectionOptions` | `MqttEndpoint(transport = WebTransportTransport(path))` from `com.ditchoom:socket-webtransport` |
| `persistence.addBroker(connectionOp, connectionRequest)` | `persistence.addBroker(connectionRequest)`, plus `endpoint` on `MqttClient.start` |
| `persistence.addBroker(listOf(a, b), connectionRequest)` | `persistence.addBroker(connectionRequest)`, plus `endpoint = a, failover = listOf(b)` on `MqttClient.start` |
| `MqttBroker.connectionOps` | None. Endpoints are passed to `start` and are not stored. |
| `MqttTransport`, `TcpMqttTransport`, `WebSocketMqttTransport`, `QuicMqttTransport`, `WebTransportMqttTransport` | A socket `Transport` on the endpoint |
| `MqttTransportResolver`, `DefaultMqttTransportResolver` | None. Choose the transport per endpoint. |
| `defaultSingleConnection(...)` | None. The client dials the endpoint itself. |
| `MqttClient.start(..., connectSingle = ...)` | `MqttClient.start(..., endpoint = MqttEndpoint(host, port, transport = yourTransport))` |
| `MqttCodec` (public) | Internal. The client frames MQTT on the transport's byte stream. |

### TCP

Before:

```kotlin
val connection = MqttConnectionOptions.SocketConnection(host = "broker.example.com", port = 1883)
val broker = persistence.addBroker(connection, connectionRequest)
val client = MqttClient.start(scope = scope, broker = broker, persistence = persistence)
```

After:

```kotlin
import com.ditchoom.mqtt.client.MqttEndpoint

val broker = persistence.addBroker(connectionRequest)
val client =
    MqttClient.start(
        scope = scope,
        broker = broker,
        persistence = persistence,
        endpoint = MqttEndpoint("broker.example.com", 1883),
    )
```

### TLS

In 2.x, TLS was on by default for port 8883 (443 for WebSocket). In 3.0.0 the port has no effect:
TLS is on only when `config.tls` is set.

Before:

```kotlin
val secure = MqttConnectionOptions.SocketConnection(host = "broker.example.com", port = 8883)

val selfSigned =
    MqttConnectionOptions.SocketConnection(
        host = "localhost",
        port = 8883,
        tlsVerifyCerts = false,
        tlsVerifyHostname = false,
        tlsAllowSelfSigned = true,
    )
```

After:

```kotlin
import com.ditchoom.mqtt.client.MqttEndpoint
import com.ditchoom.socket.TlsConfig
import com.ditchoom.socket.TransportConfig

val secure = MqttEndpoint("broker.example.com", 8883, config = TransportConfig.tlsDefault())

val selfSigned =
    MqttEndpoint(
        host = "localhost",
        port = 8883,
        config =
            TransportConfig.LOW_LATENCY.copy(
                tls = TlsConfig(verifyCertificates = false, verifyHostname = false, allowSelfSigned = true),
            ),
    )
```

### Timeouts

Before:

```kotlin
val connection =
    MqttConnectionOptions.SocketConnection(
        host = "broker.example.com",
        port = 1883,
        connectionTimeout = 5.seconds,
        readTimeout = 30.seconds,
    )
```

After:

```kotlin
import com.ditchoom.mqtt.client.MqttEndpoint
import com.ditchoom.socket.TransportConfig
import kotlin.time.Duration.Companion.seconds

val endpoint =
    MqttEndpoint(
        host = "broker.example.com",
        port = 1883,
        config = TransportConfig.LOW_LATENCY.copy(connectTimeout = 5.seconds),
        connackTimeout = 5.seconds,
    )
```

There is no read timeout. An idle session is not an error; the MQTT keep-alive detects a dead
connection.

### WebSocket

Add `com.ditchoom:websocket-tcp:2.1.0`. `mqtt-client` no longer brings it.

Before:

```kotlin
val ws =
    MqttConnectionOptions.WebSocketConnectionOptions(
        host = "broker.example.com",
        port = 443,
        websocketEndpoint = "/mqtt",
        protocols = listOf("mqtt"),
    )
```

After:

```kotlin
import com.ditchoom.mqtt.client.MqttEndpoint
import com.ditchoom.socket.TransportConfig
import com.ditchoom.websocket.tcp.WebSocketTransport

val wss =
    MqttEndpoint(
        host = "broker.example.com",
        port = 443,
        transport = WebSocketTransport(websocketEndpoint = "/mqtt", protocols = listOf("mqtt")),
        config = TransportConfig.tlsDefault(),
    )
```

`websocket-tcp` needs a TCP socket, so it does not run in the browser. In the browser, use
WebTransport or a custom `Transport`.

### QUIC

Add `com.ditchoom:socket-quic-default:4.24.0`. `mqtt-client` no longer brings it.

Before:

```kotlin
val quic =
    MqttConnectionOptions.QuicConnectionOptions(
        host = "broker.example.com",
        port = 14567,
        alpnProtocols = listOf("mqtt"),
    )
```

After:

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

`tlsVerifyCerts = false` becomes `QuicOptions(verifyPeer = false)`. See
[Transports](./recipes/transports.md#quic-experimental) for why `persistentStreams = true` matters.

### WebTransport

Add `com.ditchoom:socket-webtransport:4.24.0`. `mqtt-client` no longer brings it.

Before:

```kotlin
val wt =
    MqttConnectionOptions.WebTransportConnectionOptions(
        host = "broker.example.com",
        port = 443,
        endpoint = "/mqtt",
    )
```

After:

```kotlin
import com.ditchoom.mqtt.client.MqttEndpoint
import com.ditchoom.socket.webtransport.WebTransportTransport

val wt = MqttEndpoint("broker.example.com", 443, transport = WebTransportTransport(path = "/mqtt"))
```

### Failover

Before:

```kotlin
val broker =
    persistence.addBroker(
        listOf(
            MqttConnectionOptions.SocketConnection(host = "primary.example.com", port = 8883),
            MqttConnectionOptions.SocketConnection(host = "fallback.example.com", port = 1883),
        ),
        connectionRequest,
    )
val client = MqttClient.start(scope = scope, broker = broker, persistence = persistence)
```

After:

```kotlin
val broker = persistence.addBroker(connectionRequest)
val client =
    MqttClient.start(
        scope = scope,
        broker = broker,
        persistence = persistence,
        endpoint = MqttEndpoint("primary.example.com", 8883, config = TransportConfig.tlsDefault()),
        failover = listOf(MqttEndpoint("fallback.example.com", 1883)),
    )
```

Code that read `broker.connectionOps` must keep its endpoint list itself. Endpoints are no longer
stored in persistence, so pass them each time you call `start`.

### Custom transports

Before, a custom transport was an `MqttTransport`, an `MqttTransportResolver`, or a `connectSingle`
lambda that returned a `Connection<ControlPacket>` built with `MqttCodec`:

```kotlin
val client =
    MqttClient.start(
        scope = scope,
        broker = broker,
        persistence = persistence,
        connectSingle = { scope, options, codecForTopic -> myConnection(scope, options, codecForTopic) },
    )
```

After, implement socket's `Transport`, which returns a plain `ByteStream`. The client does the MQTT
framing:

```kotlin
import com.ditchoom.buffer.flow.ByteStream
import com.ditchoom.mqtt.client.MqttEndpoint
import com.ditchoom.socket.TransportConfig
import com.ditchoom.socket.transport.Transport

class MyTransport : Transport {
    override suspend fun connect(
        hostname: String,
        port: Int,
        config: TransportConfig,
    ): ByteStream = openMyStream(hostname, port, config)
}

val client =
    MqttClient.start(
        scope = scope,
        broker = broker,
        persistence = persistence,
        endpoint = MqttEndpoint("broker.example.com", 1883, transport = MyTransport()),
    )
```

To route the same endpoint over several transports (for example QUIC, then WebSocket), use
`FallbackTransport` in place of a custom resolver.

### Dependencies

- `mqtt-client` exposes `com.ditchoom:socket` as an `api` dependency, so `TcpTransport`,
  `FallbackTransport`, `TransportConfig` and `TlsConfig` are available without another dependency.
- `mqtt-client` no longer depends on `websocket`, `socket-quic-default` or `socket-webtransport`. Add
  the ones you use:

```kotlin
dependencies {
    implementation("com.ditchoom:mqtt-client:3.0.0")
    implementation("com.ditchoom:websocket-tcp:2.1.0")
    implementation("com.ditchoom:socket-quic-default:4.24.0")
    implementation("com.ditchoom:socket-webtransport:4.24.0")
}
```

- Built with Kotlin 2.4.21, socket 4.24.0 and buffer 6.30.9.
- Android `minSdk` is now **23**, and the library is compiled against SDK 37.

### Publishing and QoS changes

- QoS 1/2 `publish` suspends while the client is offline, until a session exists and the send quota
  has room.
- The send quota is min(`InFlightLimit`, the CONNACK's Receive Maximum). It is read again on every
  connection. See [Quality of Service](./recipes/quality-of-service.md#send-quota).
- `PublishResult.QoS1.packetId` and `PublishResult.QoS2.packetId` are now a `PacketId` value class
  instead of `Int`. Use `packetId.value` for the number.
- `QoS1State` and `QoS2State` have a new terminal state, `SessionDiscarded`: the broker started a fresh
  session before acknowledging, and the message will not be resent. Code that waits only for
  `Acknowledged` or `Complete` should also stop on `SessionDiscarded`, and exhaustive `when`
  expressions need a new branch.

## Earlier releases

This release modernizes the dependency stack and simplifies the client. Here is what changed and what
you need to update.

### Dependency bumps

- **buffer 6** (with `buffer-codec` / `buffer-flow`), **socket 3.6.x**, **Kotlin 2.4.0**.
- The TCP path is rebuilt on socket's new transport API. Update your dependency versions to the latest
  on [Maven Central](https://search.maven.org/artifact/com.ditchoom/mqtt-client) — see
  [Getting Started](./getting-started.md) for the coordinates.
- `minSdk` for the base module is now **21** (buffer 6 requires it on Android).

### In-process client replaces the service / AIDL architecture

The old `MqttService` / AIDL (Android bound-service, foreground-service IPC) architecture is **removed**
in favor of the flat, in-process [`MqttClient`](./core-concepts/mqtt-client.md).

- **Was:** bind to a service, drive it over IPC.
- **Now:** create a `Persistence`, `addBroker`, and call `MqttClient.start(...)` directly, in your own
  coroutine scope — the same on every platform, [Android](./platforms/android.md) included. See the
  [Getting Started](./getting-started.md) loop.

### Typed `Codec<P>` publish / subscribe

`publish`, `subscribe`, and `observe` are now generic over a payload type `P` with a `Codec<P>`,
replacing the raw-bytes-only surface.

- For raw bytes, use `OpaquePublishPayloadCodec` (or the untyped `publish` overload that takes a
  `ReadBuffer`).
- For typed payloads, pass your own `Codec<P>` — hand-written or KSP-generated via buffer's
  `@ProtocolMessage`. See [Typed payloads](./recipes/typed-payloads.md).

### The transport seam

Transports now live behind a composable `MqttTransport` seam selected by the
[`MqttConnectionOptions`](./core-concepts/connection-options.md) subtype and an `MqttTransportResolver`.

- **TCP** and **WebSocket** are fully supported.
- **QUIC** and **WebTransport** are implemented but experimental (single-stream; not broker-tested).
- Custom transports plug in via a resolver or the `connectSingle` hook on `MqttClient.start`.

See [Transports](./recipes/transports.md) for the full status table and caveats.
