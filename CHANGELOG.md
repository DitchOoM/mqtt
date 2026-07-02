# Changelog

All notable changes to this project are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
