---
title: Transports
---

# Transports

Every transport produces the same `Connection<ControlPacket>` behind a composable `MqttTransport`
seam, so the rest of the client is transport-agnostic. You select a transport by the
[`MqttConnectionOptions`](../core-concepts/connection-options.md) subtype you register.

| Option | Transport | Status |
|--------|-----------|--------|
| `SocketConnection` | TCP (+ TLS) | ✅ Ready |
| `WebSocketConnectionOptions` | WebSocket | 🧪 Temporarily gated (websocket lib migrating to buffer 6) |
| `QuicConnectionOptions` | QUIC (native) | 🧪 Experimental / stub |
| `WebTransportConnectionOptions` | WebTransport (incl. browser) | 🧪 Experimental / stub |

## How selection works

The `MqttConnectionOptions` subtype is mapped to a transport by an `MqttTransportResolver`. The
built-in `DefaultMqttTransportResolver` handles the standard mapping; `defaultSingleConnection(...)`
opens a single connection using it. TCP is fully supported today.

## Experimental / gated transports

- **WebSocket** — temporarily throws while `com.ditchoom:websocket` is migrated to buffer 6. The
  wiring is preserved and will be re-enabled; see `TODO.md`.
- **QUIC** — a stub that throws `NotImplementedError`. The design maps MQTT onto a single
  bidirectional stream. **Native only** — there is no raw UDP on the web. MQTT-over-QUIC is
  **non-standard** (an EMQX-style extension).
- **WebTransport** — also a stub today. It works on all targets **including the browser**, making it
  the web substitute for QUIC where UDP is unavailable. MQTT-over-WebTransport is **unspecified**.

Treat QUIC and WebTransport as experimental until they leave stub status.

## Plugging in a custom transport

You can supply your own transport without waiting on the built-ins. Two seams are available:

- Provide a custom `MqttTransportResolver` that maps an options subtype to your `MqttTransport` (a
  `fun interface`).
- Pass a `connectSingle` lambda to `MqttClient.start`, which returns a `Connection<ControlPacket>`
  for the given `MqttConnectionOptions`:

```kotlin
val client = MqttClient.start(
    scope = scope,
    broker = broker,
    persistence = persistence,
    connectSingle = { options, codecForTopic -> myConnection(options, codecForTopic) },
)
```

See [Connection options](../core-concepts/connection-options.md) for the option subtypes and their TLS
and timeout fields.
