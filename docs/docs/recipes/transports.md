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
| `WebSocketConnectionOptions` | WebSocket (+ TLS, permessage-deflate) | ✅ Ready |
| `QuicConnectionOptions` | QUIC (native) | 🧪 Experimental (implemented; not broker-tested) |
| `WebTransportConnectionOptions` | WebTransport (incl. browser) | 🧪 Experimental (implemented; not broker-tested) |

## How selection works

The `MqttConnectionOptions` subtype is mapped to a transport by an `MqttTransportResolver`. The
built-in `DefaultMqttTransportResolver` handles the standard mapping; `defaultSingleConnection(...)`
opens a single connection using it. TCP and WebSocket are fully supported today. WebSocket opens a
TCP `ByteStream` and layers the WebSocket protocol on top (HTTP upgrade + permessage-deflate), with
MQTT packets carried in binary frames.

## Experimental transports

QUIC and WebTransport are **implemented** — each tunnels the MQTT byte stream over a single
bidirectional stream (the transport opens the connection + stream and wraps it so closing the MQTT
connection tears down the whole transport). They are **not integration-tested against a broker**,
because there is no standard MQTT binding for either — treat them as experimental.

- **QUIC** (`QuicConnectionOptions`) — **native only**: the default QUIC engine throws
  `UnsupportedOperationException` on JS/wasmJs and tvOS/watchOS (no raw UDP / no engine). ALPN is
  `"mqtt"`. MQTT-over-QUIC is **non-standard** (an EMQX-style extension).
- **WebTransport** (`WebTransportConnectionOptions`) — works on all targets **including the browser**,
  making it the web substitute for QUIC where UDP is unavailable. Connects to
  `https://host:port/endpoint`. MQTT-over-WebTransport is **unspecified**.

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
