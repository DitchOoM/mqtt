---
title: Persistence
---

# Persistence

`Persistence` is where in-flight messages and subscriptions live. It backs the client's reliability
guarantees: QoS 1/2 delivery state and subscriptions can survive a process restart when you use
durable storage.

## Choosing a persistence

The recommended way is to ask the CONNECT's control-packet factory for the right implementation, so
you get the protocol-matched (v4 or v5) store:

```kotlin
// In-memory (available on every platform):
val persistence = connectionRequest.controlPacketFactory.defaultPersistence(inMemory = true)

// Durable, where supported (SQLDelight-backed):
val persistence = connectionRequest.controlPacketFactory.defaultPersistence(inMemory = false)
```

`InMemoryPersistence()` is also available directly if you always want the volatile store.

## Registering a broker

Register the broker in the persistence you'll pass to `MqttClient.start`. `addBroker` takes a single
connection option or a collection (for [failover](../recipes/reconnection-and-high-availability.md)),
plus the CONNECT:

```kotlin
val broker = persistence.addBroker(connection, connectionRequest)
```

## What survives a restart

With durable persistence, the following are stored and reloaded:

- **In-flight QoS 1 and QoS 2 messages** — unacknowledged publishes are resent after a reconnect or
  restart, so at-least-once / exactly-once semantics hold across process boundaries.
- **Subscriptions** — restored so the session continues where it left off.

In-memory persistence keeps the same state only for the life of the process.

## Per-platform availability

| Platform | Persistence |
|----------|-------------|
| JVM / Android | SQLite via SQLDelight |
| iOS / macOS / tvOS / watchOS | SQLite via SQLDelight (`-lsqlite3`) |
| Linux x64 / arm64 | SQLite via SQLDelight (`-lsqlite3`) |
| Browser | IndexedDB (SQLite-wasm planned) |
| Node.JS | In-memory (disk-backed planned) |

An in-memory `Persistence` is available on **every** platform. See the [platform pages](../platforms/jvm.md)
for details.
