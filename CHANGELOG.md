# Changelog

All notable changes to this project are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed
- Concurrent QoS 1/2 publishes (#12): outbound publishes are now owned by a single window actor that
  enforces the send quota (the smaller of `MqttClient.start(inFlightLimit = …)` and the broker's CONNACK
  Receive Maximum, re-read on every connection, including resends of a resumed session), frees quota
  held by a session the broker did not resume, and suspends publishers until a session is established
  and quota is free. `InMemoryPersistence` is lock-free and never allocates a packet identifier that is
  still in flight.

### Changed
- `PublishResult.QoS1/QoS2.packetId` is a `PacketId` value class; `QoS1State` / `QoS2State` gain a
  terminal `SessionDiscarded` state. `ControlPacketProcessor` and `ConnectivityManager` constructors are
  internal, and `ControlPacketProcessor.publish/preparePublish` are removed.
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
