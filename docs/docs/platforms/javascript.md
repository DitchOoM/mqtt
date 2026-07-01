---
title: JavaScript (Browser & Node)
---

# JavaScript (Browser & Node)

Both browser and Node.JS are supported, with differences that come from the platform's I/O APIs.

## Transport

- **Node.JS** — raw **TCP** is available (via the `Net` module), so `SocketConnection` works.
- **Browser** — there is **no raw TCP**. Use the WebSocket transport (once
  [re-enabled](../recipes/transports.md)) or [WebTransport](../recipes/transports.md), which works in
  the browser and is the web substitute for QUIC.

## Persistence

- **Browser** — IndexedDB (SQLite-wasm is planned).
- **Node.JS** — in-memory today; disk-backed persistence is planned.

See [Persistence](../core-concepts/persistence.md). An in-memory `Persistence` is available on both.

## Buffers

Native `ArrayBuffer` / `SharedArrayBuffer`, passed straight through with no `ByteArray` copies.

Usage otherwise matches the [main example](../getting-started.md).
