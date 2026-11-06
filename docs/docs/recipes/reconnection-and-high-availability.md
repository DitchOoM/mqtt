---
title: Reconnection & High Availability
---

# Reconnection & High Availability

The client stays connected for you. You never call "reconnect": once started, it re-establishes the
session after a drop and works through its endpoints for failover.

## Automatic reconnection

After a connection drop the client reconnects on its own and resumes the session. Unacknowledged
QoS 1/2 messages are resent once connectivity returns, because their delivery state is tracked (and
persisted where you use durable storage). See [Quality of Service](./quality-of-service.md) and
[Persistence](../core-concepts/persistence.md).

While the client is offline, a QoS 1/2 `publish` suspends until a session exists and the send quota
has room; see [Quality of Service](./quality-of-service.md#send-quota).

You can wait for connectivity at any time:

```kotlin
client.awaitConnectivity() // suspends until the session is live and returns the CONNACK
```

Reconnect attempts use a short fixed delay. Network-aware retry, such as switching from QUIC to
WebSocket when UDP is blocked, belongs in the endpoint's transport, for example a
[`FallbackTransport`](./transports.md#fallbacktransport).

## Failover across endpoints

Pass a primary `endpoint` and an ordered `failover` list to `MqttClient.start`:

```kotlin
import com.ditchoom.mqtt.client.MqttClient
import com.ditchoom.mqtt.client.MqttEndpoint
import com.ditchoom.socket.TransportConfig

val client =
    MqttClient.start(
        scope = scope,
        broker = broker,
        persistence = persistence,
        endpoint = MqttEndpoint("primary.example.com", 8883, config = TransportConfig.tlsDefault()),
        failover = listOf(MqttEndpoint("fallback.example.com", 1883)),
    )
```

- Each connection pass dials `endpoint` first, then each failover endpoint in order, and stops at the
  first endpoint that returns a CONNACK.
- An endpoint fails when its transport handshake exceeds `config.connectTimeout`, when the CONNACK
  does not arrive within `connackTimeout`, or when the dial throws.
- `start` returns after the first pass: either a CONNACK arrived, or every endpoint was tried. The
  client keeps retrying in the background, so call `awaitConnectivity()` if you need a live session.
- Every later pass starts again from `endpoint`, so the client returns to the primary once it is
  reachable.

Endpoints are not stored with the broker in persistence. Pass the list you want each time you call
`start`.

## Clean session vs persistent session

The CONNECT controls whether the broker keeps session state for you between connections:

- **v4**: `ConnectionRequest(..., cleanSession = ...)`. `cleanSession = true` starts fresh each time;
  `false` asks the broker to keep the session (queued messages, subscriptions).
- **v5**: `ConnectionRequest(..., cleanStart = ...)` plus the session-expiry property.
  `cleanStart = false` resumes an existing session where possible.

If the broker starts a fresh session while QoS 1/2 messages are still unacknowledged, those messages
are not resent and their state ends in `QoS1State.SessionDiscarded` or `QoS2State.SessionDiscarded`.

For durable delivery across restarts, pair a persistent session with durable
[persistence](../core-concepts/persistence.md).
