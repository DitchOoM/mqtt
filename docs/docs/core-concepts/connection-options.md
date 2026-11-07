---
title: Connection Options
---

# Connection Options

`MqttEndpoint` describes one way to reach a broker: a host, a port, the socket `Transport` that dials
it, and the `TransportConfig` used for the dial. You pass it to `MqttClient.start`, together with an
optional list of failover endpoints.

```kotlin
data class MqttEndpoint(
    val host: String,
    val port: Int,
    val transport: Transport = TcpTransport(),
    val config: TransportConfig = TransportConfig.LOW_LATENCY,
    val connackTimeout: Duration = 15.seconds,
)
```

| Field | Type | Default | Meaning |
|-------|------|---------|---------|
| `host` | `String` | | Broker host name or address. |
| `port` | `Int` | | Broker port. |
| `transport` | `com.ditchoom.socket.transport.Transport` | `TcpTransport()` | What carries the MQTT byte stream: TCP, WebSocket, QUIC, WebTransport, a `FallbackTransport` chain, or your own. |
| `config` | `com.ditchoom.socket.TransportConfig` | `TransportConfig.LOW_LATENCY` | TLS, connect timeout, buffer factory and socket tuning. |
| `connackTimeout` | `kotlin.time.Duration` | `15.seconds` | How long to wait for the broker's CONNACK after the transport is up. |

The broker's session identity (the `MqttBroker` returned by `persistence.addBroker(connectionRequest)`)
does not include the endpoint. Reachability is a runtime choice, so it is passed to `start` and is
not stored in persistence.

## TCP

```kotlin
import com.ditchoom.mqtt.client.MqttEndpoint

val endpoint = MqttEndpoint("broker.example.com", 1883)
```

## TLS

TLS is set on the config, not on the transport. Any transport whose `config.tls` is non-null runs
over TLS.

```kotlin
import com.ditchoom.socket.TransportConfig

val endpoint = MqttEndpoint("broker.example.com", 8883, config = TransportConfig.tlsDefault())
```

`TransportConfig.tlsDefault()` verifies the certificate chain and the host name. To change the
verification rules, copy a config with your own `TlsConfig`:

```kotlin
import com.ditchoom.socket.TlsConfig
import com.ditchoom.socket.TransportConfig

val selfSigned =
    TransportConfig.LOW_LATENCY.copy(
        tls = TlsConfig(verifyCertificates = false, verifyHostname = false, allowSelfSigned = true),
    )
val endpoint = MqttEndpoint("localhost", 8883, config = selfSigned)
```

| `TlsConfig` field | Default |
|-------------------|---------|
| `verifyCertificates` | `true` |
| `verifyHostname` | `true` |
| `allowExpiredCertificates` | `false` |
| `allowSelfSigned` | `false` |

`TlsConfig.DEFAULT` is the verifying configuration and `TlsConfig.INSECURE` turns every check off.
Relax verification only for local and test brokers. The port does not decide whether TLS is used.

## Timeouts and reads

- `config.connectTimeout` (default `15.seconds`) bounds the transport handshake: the TCP connect, the
  TLS handshake, and the WebSocket upgrade when there is one.
- `connackTimeout` bounds the wait for CONNACK after the transport is up.
- Reads never time out. An MQTT session can be idle between messages, so the client always connects
  with `ReadPolicy.UntilClosed` whatever `config.readPolicy` says. Liveness is the job of the MQTT
  keep-alive (`keepAliveSeconds` on the CONNECT).

```kotlin
import com.ditchoom.socket.TransportConfig
import kotlin.time.Duration.Companion.seconds

val endpoint =
    MqttEndpoint(
        host = "broker.example.com",
        port = 8883,
        config = TransportConfig.tlsDefault().copy(connectTimeout = 5.seconds),
        connackTimeout = 10.seconds,
    )
```

## Other transports and failover

WebSocket, QUIC, WebTransport, `FallbackTransport` and custom transports are all set through the
`transport` field. See [Transports](../recipes/transports.md). To try several endpoints in order,
pass them as `failover` to `MqttClient.start`; see
[Reconnection & High Availability](../recipes/reconnection-and-high-availability.md).
