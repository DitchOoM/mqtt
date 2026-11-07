---
title: JavaScript (Browser & Node)
---

# JavaScript (Browser & Node)

Both browser and Node.JS are supported, with differences that come from the platform's I/O APIs.

## Transport

- **Node.JS** — raw **TCP** is available (via the `Net` module), so the default `TcpTransport` of an
  [`MqttEndpoint`](../core-concepts/connection-options.md) works, with or without TLS. WebSocket
  (`websocket-tcp`) also works on Node.
- **Browser** — there is **no raw TCP**, so neither `TcpTransport` nor `websocket-tcp` is available.
  Use [WebTransport](../recipes/transports.md#webtransport-experimental), which uses the browser's own
  `WebTransport` object, or a [custom `Transport`](../recipes/transports.md#custom-transports).
- **QUIC** — not available on JS (no raw UDP); `QuicTransport` throws `UnsupportedOperationException`.

## Persistence

- **Browser** — IndexedDB (SQLite-wasm is planned).
- **Node.JS** — in-memory today; disk-backed persistence is planned.

See [Persistence](../core-concepts/persistence.md). An in-memory `Persistence` is available on both.

## Buffers

Native `ArrayBuffer` / `SharedArrayBuffer`, passed straight through with no `ByteArray` copies.

Usage otherwise matches the [main example](../getting-started.md).
