---
title: Apple (iOS/macOS/tvOS/watchOS)
---

# Apple (iOS / macOS / tvOS / watchOS)

Fully supported across all Apple targets from shared `appleMain` code.

- **Transport** — TCP with TLS via `NWConnection` (Network.framework): the default `TcpTransport`
  of an [`MqttEndpoint`](../core-concepts/connection-options.md). WebSocket (`websocket-tcp`) and
  WebTransport work too; QUIC works on iOS and macOS but not on tvOS or watchOS. See
  [transports](../recipes/transports.md).
- **Persistence** — SQLite via SQLDelight, linked against the system `-lsqlite3` (durable), or
  in-memory. See [Persistence](../core-concepts/persistence.md).
- **Buffers** — native `NSData`, passed straight to the socket with no `ByteArray` copies.

Usage matches the [main example](../getting-started.md); no platform-specific setup is required beyond
the standard Kotlin Multiplatform Apple toolchain.
