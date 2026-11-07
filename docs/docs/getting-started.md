---
sidebar_position: 2
title: Getting Started
---

# Getting Started

## Install

Add the client plus the protocol version(s) you need. `mqtt-client` exposes `com.ditchoom:socket`
as an `api` dependency, which gives you TCP and TLS. Other transports (WebSocket, QUIC, WebTransport)
are separate artifacts; see [Transports](./recipes/transports.md#dependencies).

```kotlin
dependencies {
    implementation("com.ditchoom:mqtt-client:3.0.0")
    implementation("com.ditchoom:mqtt-4-models:3.0.0") // MQTT 3.1.1 (v4)
    implementation("com.ditchoom:mqtt-5-models:3.0.0") // MQTT 5.0
}
```

The latest version is on [Maven Central](https://search.maven.org/artifact/com.ditchoom/mqtt-client).

## Connect, subscribe, publish

The client is **in-process**: create a `Persistence`, register a broker, and start an `MqttClient`
with an `MqttEndpoint` that says how to reach the broker. It connects and keeps itself connected
(automatic reconnection, plus failover across any extra endpoints you pass), and persists in-flight
QoS 1/2 messages.

```kotlin
import com.ditchoom.mqtt.client.MqttClient
import com.ditchoom.mqtt.client.MqttEndpoint
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.TopicFilter
import com.ditchoom.mqtt.controlpacket.OpaquePublishPayloadCodec
import com.ditchoom.mqtt3.controlpacket.ConnectionRequest // v4
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.codec.asReadBuffer
import com.ditchoom.buffer.toReadBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.take

suspend fun run(scope: CoroutineScope) {
    // 1. The session (CONNECT) and how to reach the broker (endpoint).
    val connectionRequest = ConnectionRequest(clientId = "my-client", keepAliveSeconds = 60, cleanSession = true)
    val endpoint = MqttEndpoint(host = "test.mosquitto.org", port = 1883)

    // 2. Persistence + broker registration.
    val persistence = connectionRequest.controlPacketFactory.defaultPersistence(inMemory = true)
    val broker = persistence.addBroker(connectionRequest)

    // 3. Start the client and wait for CONNACK.
    val client = MqttClient.start(scope = scope, broker = broker, persistence = persistence, endpoint = endpoint)
    client.awaitConnectivity()

    // 4. Observe messages. A Codec<P> decodes the payload; OpaquePublishPayloadCodec = raw bytes.
    val messages = client.observe(TopicFilter.fromOrThrow("test/+"), OpaquePublishPayloadCodec)
    scope.launch {
        messages.take(1).collect { (publish, payload) ->
            val text = payload.handle.asReadBuffer().let { it.readString(it.remaining(), Charset.UTF8) }
            println("Received on ${publish.topic}: $text")
        }
    }

    // 5. Subscribe (await SUBACK), then publish.
    client.subscribe("test/+", OpaquePublishPayloadCodec, QualityOfService.AT_LEAST_ONCE).subAck.await()
    client.publish(
        topicName = "test/123",
        qos = QualityOfService.EXACTLY_ONCE,
        payload = "hello".toReadBuffer(Charset.UTF8),
    )

    // 6. Clean up (drain = true waits for in-flight QoS 1/2 acks).
    client.unsubscribe("test/+").unsubAck.await()
    client.shutdown(sendDisconnect = true, drain = true)
}
```

### MQTT 5.0 instead of v4

Swap the CONNECT import — the client API is identical:

```kotlin
import com.ditchoom.mqtt5.controlpacket.ConnectionRequest // v5

val connectionRequest = ConnectionRequest(clientId = "my-client", keepAliveSeconds = 60, cleanStart = true)
```

### TLS

Put a TLS config on the endpoint:

```kotlin
import com.ditchoom.socket.TransportConfig

val endpoint = MqttEndpoint("test.mosquitto.org", 8886, config = TransportConfig.tlsDefault())
```

See [Connection options](./core-concepts/connection-options.md) for TLS rules and timeouts, and
[Transports](./recipes/transports.md) for WebSocket, QUIC and WebTransport.

## What you get for free

- **Automatic reconnection** — the client re-establishes the session and resends unacknowledged
  QoS 1/2 messages after a drop. See [Reconnection & High Availability](./recipes/reconnection-and-high-availability.md).
- **QoS 0/1/2 delivery state** — `publish` returns a `PublishResult` you can await to completion. See
  [Quality of Service](./recipes/quality-of-service.md).
- **Persistence** — in-memory everywhere, or durable SQLDelight-backed storage so in-flight messages
  survive a process restart. See [Persistence](./core-concepts/persistence.md).

## Next steps

- [The MqttClient API](./core-concepts/mqtt-client.md)
- [Connection options](./core-concepts/connection-options.md) (`MqttEndpoint`, TLS, timeouts)
- [Transports](./recipes/transports.md) (WebSocket, QUIC, WebTransport, custom transports)
- [Typed payloads](./recipes/typed-payloads.md) with your own `Codec<P>`
