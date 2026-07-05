---
title: Quality of Service
---

# Quality of Service

MQTT defines three delivery guarantees, exposed via the `QualityOfService` enum:

- **`AT_MOST_ONCE`** (QoS 0) — fire-and-forget; no acknowledgement, no retry.
- **`AT_LEAST_ONCE`** (QoS 1) — the message is delivered at least once; duplicates are possible.
- **`EXACTLY_ONCE`** (QoS 2) — the message is delivered exactly once via a four-part handshake.

## Awaiting delivery

`publish` returns a sealed `PublishResult`. Match on it to observe delivery state:

```kotlin
when (val result = client.publish(topicName = "t", qos = QualityOfService.EXACTLY_ONCE, payload = bytes)) {
    is PublishResult.QoS0Sent -> {} // handed to the transport; nothing to await
    is PublishResult.QoS1 -> result.state.first { it is QoS1State.Acknowledged }   // PUBACK received
    is PublishResult.QoS2 -> result.state.first { it is QoS2State.Complete }        // handshake done
}
```

- `QoS1(packetId, state)` — the `state: StateFlow<QoS1State>` reaches `Acknowledged` on PUBACK.
- `QoS2(packetId, state)` — the `state: StateFlow<QoS2State>` reaches `Complete` when the handshake
  finishes.

Because the state is persisted, an unacknowledged QoS 1/2 publish is resent after a reconnect or
restart — see [Persistence](../core-concepts/persistence.md) and
[Reconnection & High Availability](./reconnection-and-high-availability.md).

## QoS on subscribe

`subscribe` takes a `maxQos` (default `AT_LEAST_ONCE`) — the maximum QoS at which the broker will
deliver matching messages to you. The broker may downgrade delivery to this ceiling.

```kotlin
client.subscribe("t", OpaquePublishPayloadCodec, maxQos = QualityOfService.EXACTLY_ONCE).subAck.await()
```

## Draining on shutdown

To make sure in-flight QoS 1/2 messages finish before you stop, shut down with `drain = true`:

```kotlin
client.shutdown(sendDisconnect = true, drain = true)
```
