---
sidebar_position: 1
slug: /
title: Introduction
---

# MQTT Kotlin Multiplatform

A coroutines-based **MQTT 3.1.1 (v4)** and **5.0** client for Kotlin Multiplatform. One API on JVM,
Android, iOS/macOS/tvOS/watchOS, JS (browser + Node), and Linux — buffer-backed and zero-copy, backed
by 5000+ tests.

## Why this library?

| Concern | Without | With `com.ditchoom:mqtt-client` |
|---------|---------|---------------------------------|
| **Platform I/O** | A different MQTT stack per platform | One coroutines API everywhere |
| **Protocol** | Choose v3.1.1 *or* v5 | Both, behind one client |
| **Reliability** | Hand-roll reconnect + QoS state | Automatic reconnection, QoS 0/1/2 state machines, persistence, offline buffering |
| **Payloads** | `ByteArray` copies | Zero-copy `ReadBuffer` + typed `Codec<P>` |
| **Transport** | Rewrite for TCP vs WebSocket | Inject any socket `Transport`: TCP, TLS, WebSocket, QUIC, WebTransport, a fallback chain, or your own |
| **High availability** | Manual failover | Pass an ordered list of failover endpoints and the client works through them |

## How it fits together

```
your code ──> MqttClient ──> ConnectivityManager ──> MqttEndpoint.transport ──> ByteStream
                  │                                     (TCP / TLS / WS / QUIC / WebTransport / yours)
                  └──> Persistence (in-memory or SQLDelight)  ·  Codec<P> for typed payloads
```

- **`MqttClient`** — the in-process API you call: `publish`, `subscribe`, `observe`, `unsubscribe`.
  It stays connected for you (reconnect + failover) and tracks QoS 1/2 delivery state.
- **`Persistence`** — where in-flight messages and subscriptions live (in-memory on every platform,
  SQLDelight-backed on JVM/Android/Apple/Linux).
- **`MqttEndpoint`** — host, port, a socket `Transport` and its `TransportConfig` (TLS, connect
  timeout). The client frames MQTT on whatever byte stream the transport returns, so everything above
  it is transport-neutral.
- **`Codec<P>`** — how a payload type is encoded/decoded on the wire, zero-copy.

## Where to next

- [Getting Started](./getting-started.md) — install and run a connect → subscribe → publish loop.
- Core Concepts — [the client](./core-concepts/mqtt-client.md), [connection options (`MqttEndpoint`)](./core-concepts/connection-options.md), [persistence](./core-concepts/persistence.md).
- Recipes — [typed payloads](./recipes/typed-payloads.md), [quality of service](./recipes/quality-of-service.md), [reconnection & HA](./recipes/reconnection-and-high-availability.md), [transports](./recipes/transports.md).

> **Status note:** TCP, TLS and WebSocket are supported and tested against mosquitto, EMQX and HiveMQ.
> QUIC and WebTransport are experimental: MQTT over QUIC is non-standard (EMQX implements it) and
> MQTT over WebTransport is unspecified.
