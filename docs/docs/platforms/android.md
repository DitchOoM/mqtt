---
title: Android
---

# Android

Fully supported, sharing the JVM code paths.

- **In-process client** — the current `MqttClient` runs **in your process**. There is no AIDL service
  or foreground-service IPC to bind; you start the client directly like on any other platform. (See
  [Migration](../migration.md) if you used the old service-based architecture.)
- **Transport** — TCP with TLS via `AsynchronousSocketChannel`, or WebSocket
  ([transports](../recipes/transports.md)).
- **Persistence** — SQLite via SQLDelight (durable), or in-memory. See
  [Persistence](../core-concepts/persistence.md).
- **Buffers** — native `ByteBuffer`, no `ByteArray` copies on the hot path.
- **minSdk** — 21.

Usage matches the [main example](../getting-started.md). Manage the client from a coroutine scope tied
to whatever component owns its lifetime.
