# Changelog

All notable changes to this project are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed
- Migrated to **buffer 6.0.0** (`ByteSource`/`ByteSink`/`ByteStream` byte-layer, `ReadPolicy`/`WritePolicy`)
  and **socket 3.6.10** (single immutable `TransportConfig`; the socket is now a `ByteStream`).
- Upgraded the toolchain to **Kotlin 2.4.0** + **KSP 2.3.9**.
- Consolidated transport selection behind a composable `MqttTransport` seam (`MqttTransportResolver` +
  per-transport implementations) so custom or alternative transports can be plugged in.
- Persistent connections now use `ReadPolicy.UntilClosed`; the CONNECT handshake is explicitly bounded.
- License changed to **MIT** (a `LICENSE` file and consistent POM metadata are now published).

### Added
- Experimental transport scaffolding for **QUIC** (`QuicConnectionOptions` / `QuicMqttTransport`) and
  **WebTransport** (`WebTransportConnectionOptions` / `WebTransportMqttTransport`) — currently stubs mapping
  MQTT onto a single bidirectional stream. WebTransport is the intended web substitute for QUIC (no UDP on web).
- Codec wire-format snapshot gate (`com.ditchoom.buffer.codec-schema`) on the model modules; a committed
  baseline fails the build on any breaking wire-format drift.
- `linuxArm64` target re-enabled and published; `linuxX64Test` added to CI.

### Deprecated / Temporarily disabled
- The **WebSocket** transport is temporarily gated pending the `websocket` library's migration to buffer 6.

## [1.2.0]

Baseline release. See the Git history for changes prior to this changelog.

[Unreleased]: https://github.com/DitchOoM/mqtt/compare/v1.2.0...HEAD
[1.2.0]: https://github.com/DitchOoM/mqtt/releases/tag/v1.2.0
