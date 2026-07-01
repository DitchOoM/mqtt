---
title: JVM
---

# JVM

Fully supported. MQTT 3.1.1 (v4) and 5.0, automatic reconnection, offline buffering, and high
availability all work on the JVM.

- **Transport** — TCP with TLS, via `AsynchronousSocketChannel` (falling back to `SocketChannel`).
  Select it with `MqttConnectionOptions.SocketConnection`. WebSocket is
  [temporarily gated](../recipes/transports.md).
- **Persistence** — SQLite via SQLDelight (durable), or in-memory. See
  [Persistence](../core-concepts/persistence.md).
- **Buffers** — native `ByteBuffer`, passed straight to the socket with no `ByteArray` copies.
- **JDK** — targets a modern LTS JDK; use JDK 17+ to build and run.

Getting started is identical to the [main example](../getting-started.md); no platform-specific setup
is required.
