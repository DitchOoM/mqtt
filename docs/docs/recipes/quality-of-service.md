---
title: Quality of Service
---

# Quality of Service

MQTT defines three delivery guarantees, exposed via the `QualityOfService` enum:

- **`AT_MOST_ONCE`** (QoS 0): fire-and-forget; no acknowledgement, no retry.
- **`AT_LEAST_ONCE`** (QoS 1): the message is delivered at least once; duplicates are possible.
- **`EXACTLY_ONCE`** (QoS 2): the message is delivered exactly once via a four-part handshake.

## Awaiting delivery

`publish` returns a sealed `PublishResult`. Match on it to observe delivery state:

```kotlin
when (val result = client.publish(topicName = "t", qos = QualityOfService.EXACTLY_ONCE, payload = bytes)) {
    PublishResult.QoS0Sent -> Unit // handed to the transport; nothing to await
    is PublishResult.QoS1 -> result.state.first { it is QoS1State.Acknowledged || it is QoS1State.SessionDiscarded }
    is PublishResult.QoS2 -> result.state.first { it is QoS2State.Complete || it is QoS2State.SessionDiscarded }
}
```

- `QoS1(packetId, state)`: `state: StateFlow<QoS1State>` moves `Queued` → `Sent` → `Acknowledged`
  on PUBACK.
- `QoS2(packetId, state)`: `state: StateFlow<QoS2State>` moves `Queued` → `Sent` → `Received` →
  `Released` → `Complete` on PUBCOMP.
- `packetId` is a `com.ditchoom.mqtt.controlpacket.PacketId` value class; read the number with
  `packetId.value`.

Both state machines have a second terminal state, `SessionDiscarded`. It means the broker started a
fresh session before the message was acknowledged, so the client will not resend it. Wait for either
terminal state, as above, or the wait never ends when a session is discarded.

Because the state is persisted, an unacknowledged QoS 1/2 publish is resent after a reconnect or
restart while the broker keeps the session. See [Persistence](../core-concepts/persistence.md) and
[Reconnection & High Availability](./reconnection-and-high-availability.md).

## Send quota

QoS 1/2 `publish` suspends until two things are true: a session exists, and the send quota has room.
The quota is the smaller of the client's `InFlightLimit` and the broker's Receive Maximum from the
CONNACK. It is read again on every connection, so a broker that lowers its Receive Maximum is honored
after a reconnect. MQTT 3.1.1 has no Receive Maximum, so there only `InFlightLimit` applies.

```kotlin
import com.ditchoom.mqtt.client.InFlightLimit

val client =
    MqttClient.start(
        scope = scope,
        broker = broker,
        persistence = persistence,
        endpoint = endpoint,
        inFlightLimit = InFlightLimit(32),
    )
```

The default is `InFlightLimit.ProtocolMaximum` (65,535), so the broker's Receive Maximum usually
decides.

## QoS on subscribe

`subscribe` takes a `maxQos` (default `AT_LEAST_ONCE`): the maximum QoS at which the broker will
deliver matching messages to you. The broker may downgrade delivery to this ceiling.

```kotlin
client.subscribe("t", OpaquePublishPayloadCodec, maxQos = QualityOfService.EXACTLY_ONCE).subAck.await()
```

## Draining on shutdown

To make sure in-flight QoS 1/2 messages finish before you stop, shut down with `drain = true`:

```kotlin
client.shutdown(sendDisconnect = true, drain = true)
```
