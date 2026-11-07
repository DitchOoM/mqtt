---
title: Linux
---

# Linux

Fully supported on `linuxX64` and `linuxArm64`.

- **Transport** — TCP with TLS using **io_uring** (falling back to **epoll**): the default
  `TcpTransport` of an [`MqttEndpoint`](../core-concepts/connection-options.md). QUIC and
  WebTransport are separate artifacts; WebSocket (`websocket-tcp`) is published for both, from 2.1.1.
  See [transports](../recipes/transports.md).
- **Persistence** — SQLite via SQLDelight, linked against `-lsqlite3` (durable), or in-memory. See
  [Persistence](../core-concepts/persistence.md).
- **Buffers** — native `NativeBuffer`, passed straight to the socket with no `ByteArray` copies.

Usage matches the [main example](../getting-started.md); no platform-specific setup is required beyond
a working `libsqlite3` for durable persistence.
