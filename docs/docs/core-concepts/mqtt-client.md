---
title: The MqttClient API
---

# The MqttClient API

`MqttClient` is the in-process API you interact with. You start one against a registered broker, and
it keeps itself connected for you — automatic reconnection plus failover across the endpoints you
pass — while tracking QoS 1/2 delivery state. There is no background service or IPC to
manage; the client lives in your process and coroutine scope.

## Lifecycle

```kotlin
val client =
    MqttClient.start(
        scope = scope,
        broker = broker,
        persistence = persistence,
        endpoint = MqttEndpoint("broker.example.com", 1883),
    )
client.awaitConnectivity() // suspends until CONNACK; returns IConnectionAcknowledgment

// ... publish / subscribe / observe ...

client.shutdown(sendDisconnect = true, drain = true)
```

- **`start(...)`** — creates the client and begins connecting. The client stays connected until you
  shut it down or cancel `scope`. Its parameters:

  | Parameter | Default | Meaning |
  |-----------|---------|---------|
  | `scope` | `CoroutineScope(Dispatchers.Default + CoroutineName("MQTT Client"))` | Owns the connection; cancelling it stops the client. |
  | `broker` | | The `MqttBroker` returned by `persistence.addBroker(connectionRequest)`. |
  | `persistence` | | The `Persistence` the broker was registered in. |
  | `endpoint` | | The [`MqttEndpoint`](./connection-options.md) dialled first. |
  | `failover` | `emptyList()` | Endpoints dialled in order when `endpoint` fails. See [failover](../recipes/reconnection-and-high-availability.md#failover-across-endpoints). |
  | `bufferFactory` | `BufferFactory.Default` | Allocates outgoing payload buffers. |
  | `inFlightLimit` | `InFlightLimit.ProtocolMaximum` | Upper bound on unacknowledged QoS 1/2 publishes. See [send quota](../recipes/quality-of-service.md#send-quota). |
  | `timeSource` | `TimeSource.Monotonic` | Clock for keep-alive timing. |

  `start` suspends until the first connection pass ends: a CONNACK arrived, or every endpoint was
  tried.
- **`awaitConnectivity()`** — suspends until the client has a live session and returns the
  `IConnectionAcknowledgment` (CONNACK).
- **`sendDisconnect()`** — sends an MQTT DISCONNECT without tearing the client down.
- **`shutdown(sendDisconnect = true, drain = false)`** — stops the client. With `drain = true` it
  first waits for in-flight QoS 1/2 acknowledgements; see [Quality of Service](../recipes/quality-of-service.md).

## The publish / subscribe surface

The client exposes four operations, all generic over a payload type `P` with a `Codec<P>`:

- **`publish(...)`** — returns a [`PublishResult`](../recipes/quality-of-service.md) you can await to
  completion. There is an untyped overload taking a raw `ReadBuffer`, and a typed overload taking
  `payload: P` + `payloadCodec: Codec<P>`. QoS 1/2 publishes suspend while the client is offline or
  the [send quota](../recipes/quality-of-service.md#send-quota) is full.
- **`subscribe(topicFilter, payloadCodec, maxQos)`** — returns a `SubscribeOperation<P>`; await
  `subAck.await()` for the SUBACK. A handler overload delivers messages via
  `suspend (PublishMessage, P) -> Unit`.
- **`observe(filter, payloadCodec)`** — returns a `Flow<Pair<PublishMessage, P>>` of decoded messages.
- **`unsubscribe(topicFilter)`** — returns an `UnsubscribeOperation`; await `unsubAck.await()`.

See the full connect → subscribe → publish loop in [Getting Started](../getting-started.md).

## Where to next

- [Connection options](./connection-options.md) — `MqttEndpoint`: how and where the client connects.
- [Transports](../recipes/transports.md) — TCP, TLS, WebSocket, QUIC, WebTransport and custom transports.
- [Persistence](./persistence.md) — what state the client keeps across restarts.
- [Typed payloads](../recipes/typed-payloads.md) — defining and using your own `Codec<P>`.
