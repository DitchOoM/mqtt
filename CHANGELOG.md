# Changelog

All notable changes to this project are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [3.0.0]

A breaking release: lock-free QoS 1/2 send quota (#12), any socket `Transport` through one
`MqttEndpoint`, and a current toolchain. See **Migrating from 2.x** below.

### Breaking
- **Transport injection.** `MqttConnectionOptions` (`SocketConnection`, `WebSocketConnectionOptions`,
  `QuicConnectionOptions`, `WebTransportConnectionOptions`) is replaced by
  `MqttEndpoint(host, port, transport, config, connackTimeout)`, which carries any socket `Transport`:
  TCP (default), TLS through `TransportConfig.tls`, WebSocket (`WebSocketTransport` from
  `com.ditchoom:websocket-tcp`), QUIC, WebTransport, `FallbackTransport` chains, `MemoryTransport`, or
  your own.
- The `tlsEnabled`, `tlsVerifyCerts`, `tlsVerifyHostname`, `tlsAllowExpired` and `tlsAllowSelfSigned`
  flags are removed. TLS is `TransportConfig(tls = TlsConfig(...))`.
- `MqttClient.start(scope, broker, persistence, endpoint, failover = emptyList(), ...)`: the
  `connectSingle` parameter is removed, and endpoints are passed here (a broker can no longer have zero
  endpoints). `MqttTransport`, `MqttTransportResolver`, `TcpMqttTransport`, `WebSocketMqttTransport`,
  `QuicMqttTransport`, `WebTransportMqttTransport` and `defaultSingleConnection` are removed.
  `MqttCodec` is internal.
- `MqttBroker(identifier, connectionRequest)` no longer holds `connectionOps`, and
  `Persistence.addBroker(connectionRequest)` replaces both `addBroker` overloads.
- Dependencies: `com.ditchoom:socket` is now an `api` dependency of mqtt-client (its `Transport` and
  `TransportConfig` are in the public API), and mqtt-client no longer pulls in `websocket`,
  `socket-quic-default` or `socket-webtransport`. Add the transport artifact you use.
- QoS 1/2 `publish` **suspends while offline** until a session is established and send quota is free.
  It no longer goes out ahead of the quota.
- `PublishResult.QoS1/QoS2.packetId` is a `PacketId` value class. `QoS1State` and `QoS2State` gain a
  terminal `SessionDiscarded` state for publishes the broker did not resume.
- `MqttClient.start` gains `inFlightLimit` and `timeSource` parameters. `clearMessages` is scoped to its
  broker. `pendingPublishes()` includes publishes restored from persistence.
- `ControlPacketProcessor` and `ConnectivityManager` constructors are internal, and
  `ControlPacketProcessor.publish/preparePublish` are removed.
- Android: `minSdk` 21 to **23** and `compileSdk` **37**, as required by socket 4.24.
- socket 3.10+ behaviour: a write whose `WritePolicy.Bounded` deadline expires closes the connection,
  and the client then reconnects.

### Fixed
- Concurrent QoS 1/2 publishes (#12). A single window actor owns outbound publishes and enforces the send
  quota: the smaller of `inFlightLimit` and the broker's CONNACK Receive Maximum, re-read on every
  connection, including resends of a resumed session. It frees the quota held by a session the broker
  did not resume, and suspends publishers until quota is free. One writer is the only caller of `send`.
  Packet identifiers are allocated lock-free and never collide with an id still in flight.
- A PUBLISH with an ill-formed UTF-8 string (buffer's `MalformedTextException.IllFormedBytes`) is now
  reported as `MalformedPacketException` instead of escaping as a raw exception.

### Changed
- Toolchain: Gradle **9.9.0**, AGP **9.5.1** (classic DSL through AGP 9's transitional flags), Kotlin
  **2.4.21**, KSP **2.3.13**.
- Dependencies: socket **4.24.0**, buffer **6.30.8**, websocket **2.1.0**, sqldelight 2.4.1,
  kotlinx-benchmark 0.5.0, maven-publish 0.37.0, dokka 2.3.0. Test dependencies: testcontainers
  2.0.5, JUnit 6.1.3, jazzer 0.30.0.
- A connection always reads with `ReadPolicy.UntilClosed`, whatever `TransportConfig` it is given, so an
  idle session never fails on a read deadline. `TransportConfig.connectTimeout` bounds the transport
  handshake, and `MqttEndpoint.connackTimeout` bounds the CONNACK wait.

### Added
- CI: a real-broker interop matrix (mosquitto, EMQX and HiveMQ CE, over MQTT 3.1.1 and 5, over TCP, TLS
  and WebSocket), a binary-compatibility gate (`checkKotlinAbi` with committed JVM, Android and klib
  dumps), arm64 Linux and x86_64 macOS legs, wasmJs tests, Paho conformance on linuxX64, a nightly
  stress and fuzz run, and a downstream canary against buffer, socket and websocket `main`.

### Migrating from 2.x

| 2.x | 3.0.0 |
|---|---|
| `MqttConnectionOptions.SocketConnection(host, 1883)` | `MqttEndpoint(host, 1883)` |
| `SocketConnection(host, 8883, tlsEnabled = true)` | `MqttEndpoint(host, 8883, config = TransportConfig.tlsDefault())` |
| `tlsAllowSelfSigned = true` (etc.) | `config = TransportConfig.LOW_LATENCY.copy(tls = TlsConfig(allowSelfSigned = true))` |
| `WebSocketConnectionOptions(host, 443, tlsEnabled = true, websocketEndpoint = "/mqtt")` | `MqttEndpoint(host, 443, WebSocketTransport("/mqtt", listOf("mqtt")), TransportConfig.tlsDefault())` + `com.ditchoom:websocket-tcp` |
| `QuicConnectionOptions(host, port)` | `MqttEndpoint(host, port, QuicTransport(QuicOptions(alpnProtocols = listOf("mqtt"))))` + `com.ditchoom:socket-quic-default` |
| `WebTransportConnectionOptions(host, port, endpoint = "/mqtt")` | `MqttEndpoint(host, port, WebTransportTransport(path = "/mqtt"))` + `com.ditchoom:socket-webtransport` |
| `connectionTimeout = 10.seconds` | `config = TransportConfig.LOW_LATENCY.copy(connectTimeout = 10.seconds)`, `connackTimeout = 10.seconds` |
| `persistence.addBroker(listOf(primary, backup), request)` | `persistence.addBroker(request)`, then `start(..., endpoint = primary, failover = listOf(backup))` |
| `MqttClient.start(scope, broker, persistence, connectSingle = custom)` | implement a socket `Transport` and pass it in an `MqttEndpoint` |
| `MqttTransportResolver` (e.g. QUIC on native, WebTransport on web) | `FallbackTransport(listOf(quic, webTransport, TcpTransport()))`, or choose the endpoint per platform |
| `publishResult.packetId: Int` | `publishResult.packetId.value` |

```kotlin
// 2.x
val broker = persistence.addBroker(
    MqttConnectionOptions.SocketConnection("broker.example", 8883, tlsEnabled = true),
    connectionRequest,
)
val client = MqttClient.start(scope, broker, persistence)

// 3.0.0
val broker = persistence.addBroker(connectionRequest)
val client = MqttClient.start(
    scope,
    broker,
    persistence,
    MqttEndpoint("broker.example", 8883, config = TransportConfig.tlsDefault()),
)
```

Publishing QoS 1/2 before the first CONNACK now waits for the session instead of failing or racing the
quota, so code that published and then started the client should start it first, or publish from a
coroutine it is willing to have suspended.

## [2.0.0] to [2.1.1]
### Changed
- Migrated to **buffer 6.3.0** (`ByteSource`/`ByteSink`/`ByteStream` byte-layer, `ReadPolicy`/`WritePolicy`)
  and **socket 3.8.3** (single immutable `TransportConfig`; the socket is now a `ByteStream`).
- Upgraded the toolchain to **Kotlin 2.4.0** + **KSP 2.3.9**.
- Consolidated transport selection behind a composable `MqttTransport` seam (`MqttTransportResolver` +
  per-transport implementations) so custom or alternative transports can be plugged in.
- Persistent connections now use `ReadPolicy.UntilClosed`; the CONNECT handshake is explicitly bounded.
- License changed to **MIT** (a `LICENSE` file and consistent POM metadata are now published).

### Added
- Experimental **QUIC** (`QuicConnectionOptions` / `QuicMqttTransport`) and **WebTransport**
  (`WebTransportConnectionOptions` / `WebTransportMqttTransport`) transports, each tunneling MQTT over a
  single bidirectional stream. QUIC is native-only; WebTransport works everywhere including the browser
  (the web substitute for QUIC, since there is no raw UDP on the web). Not integration-tested against a
  broker — neither mapping is standardized.
- Codec wire-format snapshot gate (`com.ditchoom.buffer.codec-schema`) on the model modules; a committed
  baseline fails the build on any breaking wire-format drift.
- `linuxArm64` target re-enabled and published; `linuxX64Test` added to CI.
- **WebSocket** transport (via **websocket 2.0.2**, built on buffer 6) with TLS and permessage-deflate.

## [1.2.0]

Baseline release. See the Git history for changes prior to this changelog.

[Unreleased]: https://github.com/DitchOoM/mqtt/compare/v1.2.0...HEAD
[1.2.0]: https://github.com/DitchOoM/mqtt/releases/tag/v1.2.0
