---
title: The MqttClient API
---

# The MqttClient API

`MqttClient` is the in-process API you interact with. You start one against a registered broker, and
it keeps itself connected for you — automatic reconnection plus failover across the broker's
connection options — while tracking QoS 1/2 delivery state. There is no background service or IPC to
manage; the client lives in your process and coroutine scope.

## Lifecycle

```kotlin
val client = MqttClient.start(scope = scope, broker = broker, persistence = persistence)
client.awaitConnectivity() // suspends until CONNACK; returns IConnectionAcknowledgment

// ... publish / subscribe / observe ...

client.shutdown(sendDisconnect = true, drain = true)
```

- **`start(...)`** — creates the client and begins connecting. It takes the coroutine `scope`, the
  `broker`, and the `persistence` it was registered in (plus an optional `bufferFactory` and a
  `connectSingle` hook for [custom transports](../recipes/transports.md)). The client stays connected
  until you shut it down.
- **`awaitConnectivity()`** — suspends until the client has a live session and returns the
  `IConnectionAcknowledgment` (CONNACK).
- **`sendDisconnect()`** — sends an MQTT DISCONNECT without tearing the client down.
- **`shutdown(sendDisconnect = true, drain = false)`** — stops the client. With `drain = true` it
  first waits for in-flight QoS 1/2 acknowledgements; see [Quality of Service](../recipes/quality-of-service.md).

## The publish / subscribe surface

The client exposes four operations, all generic over a payload type `P` with a `Codec<P>`:

- **`publish(...)`** — returns a [`PublishResult`](../recipes/quality-of-service.md) you can await to
  completion. There is an untyped overload taking a raw `ReadBuffer`, and a typed overload taking
  `payload: P` + `payloadCodec: Codec<P>`.
- **`subscribe(topicFilter, payloadCodec, maxQos)`** — returns a `SubscribeOperation<P>`; await
  `subAck.await()` for the SUBACK. A handler overload delivers messages via
  `suspend (PublishMessage, P) -> Unit`.
- **`observe(filter, payloadCodec)`** — returns a `Flow<Pair<PublishMessage, P>>` of decoded messages.
- **`unsubscribe(topicFilter)`** — returns an `UnsubscribeOperation`; await `unsubAck.await()`.

See the full connect → subscribe → publish loop in [Getting Started](../getting-started.md).

## Where to next

- [Connection options](./connection-options.md) — how and where the client connects.
- [Persistence](./persistence.md) — what state the client keeps across restarts.
- [Typed payloads](../recipes/typed-payloads.md) — defining and using your own `Codec<P>`.
