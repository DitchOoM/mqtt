---
title: Reconnection & High Availability
---

# Reconnection & High Availability

The client stays connected for you. You never call "reconnect" — once started, it re-establishes the
session after a drop and works through the broker's connection options for failover.

## Automatic reconnection

After a connection drop the client reconnects on its own and resumes the session. Unacknowledged
QoS 1/2 messages are **resent** once connectivity returns, because their delivery state is tracked (and
persisted where you use durable storage). See [Quality of Service](./quality-of-service.md) and
[Persistence](../core-concepts/persistence.md).

You can wait for connectivity at any time:

```kotlin
client.awaitConnectivity() // suspends until the session is live (returns the CONNACK)
```

## Failover across connection options

A broker holds a **collection** of [connection options](../core-concepts/connection-options.md). List
more than one and the client fails over between them — for example a primary endpoint plus a fallback:

```kotlin
val broker = persistence.addBroker(
    listOf(
        MqttConnectionOptions.SocketConnection(host = "primary.example.com", port = 8883),
        MqttConnectionOptions.SocketConnection(host = "fallback.example.com", port = 1883),
    ),
    connectionRequest,
)
```

The client picks a working option and moves to another when one is unreachable.

## Clean session vs persistent session

The CONNECT controls whether the broker keeps session state for you between connections:

- **v4** — `ConnectionRequest(..., cleanSession = ...)`. `cleanSession = true` starts fresh each time;
  `false` asks the broker to retain the session (queued messages, subscriptions).
- **v5** — `ConnectionRequest(..., cleanStart = ...)` (plus session-expiry properties). `cleanStart =
  false` resumes an existing session where possible.

For durable delivery across restarts, pair a persistent session with durable
[persistence](../core-concepts/persistence.md).
