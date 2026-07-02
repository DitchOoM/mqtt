---
title: Migration
---

# Migration

This release modernizes the dependency stack and simplifies the client. Here is what changed and what
you need to update.

## Dependency bumps

- **buffer 6** (with `buffer-codec` / `buffer-flow`), **socket 3.6.x**, **Kotlin 2.4.0**.
- The TCP path is rebuilt on socket's new transport API. Update your dependency versions to the latest
  on [Maven Central](https://search.maven.org/artifact/com.ditchoom/mqtt-client) — see
  [Getting Started](./getting-started.md) for the coordinates.
- `minSdk` for the base module is now **21** (buffer 6 requires it on Android).

## In-process client replaces the service / AIDL architecture

The old `MqttService` / AIDL (Android bound-service, foreground-service IPC) architecture is **removed**
in favor of the flat, in-process [`MqttClient`](./core-concepts/mqtt-client.md).

- **Was:** bind to a service, drive it over IPC.
- **Now:** create a `Persistence`, `addBroker`, and call `MqttClient.start(...)` directly, in your own
  coroutine scope — the same on every platform, [Android](./platforms/android.md) included. See the
  [Getting Started](./getting-started.md) loop.

## Typed `Codec<P>` publish / subscribe

`publish`, `subscribe`, and `observe` are now generic over a payload type `P` with a `Codec<P>`,
replacing the raw-bytes-only surface.

- For raw bytes, use `OpaquePublishPayloadCodec` (or the untyped `publish` overload that takes a
  `ReadBuffer`).
- For typed payloads, pass your own `Codec<P>` — hand-written or KSP-generated via buffer's
  `@ProtocolMessage`. See [Typed payloads](./recipes/typed-payloads.md).

## The transport seam

Transports now live behind a composable `MqttTransport` seam selected by the
[`MqttConnectionOptions`](./core-concepts/connection-options.md) subtype and an `MqttTransportResolver`.

- **TCP** and **WebSocket** are fully supported.
- **QUIC** and **WebTransport** are experimental stubs.
- Custom transports plug in via a resolver or the `connectSingle` hook on `MqttClient.start`.

See [Transports](./recipes/transports.md) for the full status table and caveats.
