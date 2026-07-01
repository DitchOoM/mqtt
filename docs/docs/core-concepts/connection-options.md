---
title: Connection Options
---

# Connection Options

`MqttConnectionOptions` describes *how and where* the client connects. It is a sealed type, and each
subtype selects a [transport](../recipes/transports.md). A broker holds a **collection** of connection
options, and the client works through that list for high-availability failover.

## The subtypes

```kotlin
// TCP (+ TLS). This is the fully-supported transport today.
val tcp = MqttConnectionOptions.SocketConnection(host = "broker.example.com", port = 1883)

// WebSocket (temporarily gated — see the transports recipe).
val ws = MqttConnectionOptions.WebSocketConnectionOptions(
    host = "broker.example.com",
    port = 443,
    websocketEndpoint = "/mqtt",
    protocols = listOf("mqtt"),
)
```

There are also `QuicConnectionOptions(host, port, alpnProtocols = listOf("mqtt"), ...)` and
`WebTransportConnectionOptions(host, port, ..., endpoint = "/mqtt")` — both experimental. See
[Transports](../recipes/transports.md) for their status and caveats.

## TLS and timeouts

`SocketConnection` (and the WebSocket option) expose TLS controls. TLS defaults to on when the port is
the conventional secure port (`tlsEnabled = port == 8883`), and the verification knobs default to the
safe values:

| Field | Default |
|-------|---------|
| `tlsEnabled` | `port == 8883` (`443` for WebSocket) |
| `tlsVerifyCerts` | `true` |
| `tlsVerifyHostname` | `true` |
| `tlsAllowExpired` | `false` |
| `tlsAllowSelfSigned` | `false` |

```kotlin
val tls = MqttConnectionOptions.SocketConnection(host = "broker.example.com", port = 8883)
// tlsEnabled is true here because port == 8883
```

Every subtype also carries `connectionTimeout` (default `15.seconds`), plus `readTimeout` and
`writeTimeout`. Relax the verification flags only for local/test brokers.

## Failover

Because a broker is created from a *collection* of options, you can list several — for example a TLS
endpoint plus a plaintext fallback, or two hosts — and the client fails over between them
automatically. See [Reconnection & High Availability](../recipes/reconnection-and-high-availability.md).
