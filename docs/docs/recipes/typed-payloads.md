---
title: Typed Payloads
---

# Typed Payloads

`publish`, `subscribe`, and `observe` are generic over a payload type `P` and take a `Codec<P>` that
encodes/decodes it on the wire — zero-copy. This lets you work with your domain types directly instead
of hand-marshalling bytes.

## Raw bytes with `OpaquePublishPayloadCodec`

`OpaquePublishPayloadCodec` is the built-in codec for raw application bytes. It decodes to an
`OpaquePublishPayload` whose `.handle.asReadBuffer()` gives you the bytes — decode them however you
like. There is no built-in string codec, so read the buffer directly:

```kotlin
client.subscribe("t", OpaquePublishPayloadCodec, QualityOfService.AT_LEAST_ONCE).subAck.await()

client.observe(TopicFilter.fromOrThrow("t"), OpaquePublishPayloadCodec).collect { (publish, payload) ->
    val text = payload.handle.asReadBuffer().let { it.readString(it.remaining(), Charset.UTF8) }
    println("Received on ${publish.topic}: $text")
}
```

To publish raw bytes, use the untyped `publish` overload with a `ReadBuffer`:

```kotlin
client.publish(topicName = "t", payload = "hello".toReadBuffer(Charset.UTF8))
```

## Your own `Codec<P>`

For a typed payload, supply a `Codec<P>` (where `P : Payload`) to the typed overloads:

```kotlin
// Publish a typed value:
client.publish(topic = "t", payload = myValue, payloadCodec = MyCodec, qos = QualityOfService.AT_LEAST_ONCE)

// Observe decoded values:
client.observe(TopicFilter.fromOrThrow("t"), MyCodec).collect { (publish, value) -> /* value: P */ }

// Or handle them at subscribe time:
client.subscribe("t", MyCodec, QualityOfService.AT_LEAST_ONCE) { publish, value -> /* value: P */ }
```

Codecs can be **hand-written** or **KSP-generated** from a model annotated with buffer's
`@ProtocolMessage`, which produces the encode/decode logic with no hand-written wire layer.

## Where to next

- [Quality of Service](./quality-of-service.md) — the `PublishResult` returned by `publish`.
- [The MqttClient API](../core-concepts/mqtt-client.md) — the full operation surface.
