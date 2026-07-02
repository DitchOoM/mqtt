[![Contributors][contributors-shield]][contributors-url]
[![Forks][forks-shield]][forks-url]
[![Stargazers][stars-shield]][stars-url]
[![Issues][issues-shield]][issues-url]
[![MIT License][license-shield]][license-url]
[![LinkedIn][linkedin-shield]][linkedin-url]


<!-- PROJECT LOGO -->
<!--suppress ALL -->

<br />
<p align="center">
<h3 align="center">MQTT Kotlin Multiplatform</h3>

<p align="center">Coroutines-based Kotlin Multiplatform MQTT 3.1.1 (v4) &amp; 5.0 client. Buffer-backed, zero-copy, backed by 5000+ tests.</p>
<br />
See the [project website][docs] for guides and API reference.
<br />
<br />
<a href="https://github.com/DitchOoM/mqtt/issues">Report Bug</a>
·
<a href="https://github.com/DitchOoM/mqtt/issues">Request Feature</a>
</p>


<details open="open">
  <summary>Table of Contents</summary>
  <ol>
    <li>
      <a href="#about-the-project">About The Project</a>
      <ul>
        <li><a href="#runtime-dependencies">Runtime Dependencies</a></li>
        <li><a href="#supported-platforms">Supported Platforms</a></li>
      </ul>
    </li>
    <li><a href="#installation">Installation</a></li>
    <li><a href="#usage">Usage</a></li>
    <li><a href="#transports">Transports</a></li>
    <li><a href="#building-locally">Building Locally</a></li>
    <li><a href="#roadmap">Roadmap</a></li>
    <li><a href="#contributing">Contributing</a></li>
    <li><a href="#license">License</a></li>
  </ol>
</details>

## About The Project

This project simplifies managing an MQTT client across multiple platforms with a single Kotlin API.

### Why MQTT (this library)?

| Concern | Without | With `com.ditchoom:mqtt-client` |
|---------|---------|---------------------------------|
| **Platform I/O** | Separate MQTT stacks per platform | One coroutines API on JVM, Android, iOS/macOS/tvOS/watchOS, JS (browser + Node), Linux |
| **Protocol** | Pick a v3.1.1 *or* v5 library | Both MQTT 3.1.1 (v4) and 5.0 behind one client |
| **Reliability** | Hand-roll reconnect + QoS state | Automatic reconnection, QoS 0/1/2 state machines, message persistence, offline buffering |
| **Payloads** | `ByteArray` copies everywhere | Zero-copy `ReadBuffer` + typed `Codec<P>` payloads |
| **Transport** | Rewrite for TCP vs WebSocket | Composable transport seam (TCP today; WebSocket, QUIC, WebTransport on the same seam) |
| **High availability** | Manual failover | A broker can list multiple connection options and fail over between them |

### Implementation notes

* A fully asynchronous, coroutines-based implementation with a minimal memory footprint for low-memory devices.
* Domain models are annotation-driven codecs (`@ProtocolMessage`) — no hand-written wire layer — allowing customization or custom protocols derived from MQTT without a full rewrite.
* Buffers are native and passed straight to the socket, avoiding `ByteArray` copies on the hot path.

Buffer uses native buffers to pass to the socket:

|        Platform        |       Native Buffer Type        |
|:----------------------:|:-------------------------------:|
|     Android / JVM      |           ByteBuffer            |
| iOS/macOS/tvOS/watchOS |             NSData              |
|    BrowserJS/NodeJS    | ArrayBuffer / SharedArrayBuffer |
|    Linux x64/arm64     |          NativeBuffer           |

Socket uses native socket APIs:

|        Platform        |                  Native Socket Impl                  |
|:----------------------:|:----------------------------------------------------:|
|      Android/JVM       | AsynchronousSocketChannel (fallback SocketChannel)   |
| iOS/macOS/tvOS/watchOS |                     NWConnection                     |
|         NodeJS         |                      Net module                      |
|       BrowserJS        |          unavailable (use WebSocket / WebTransport)  |
|    Linux x64/arm64     |                   io_uring / epoll                   |

Persistence uses:

|        Platform        |                Persistence Impl                 |
|:----------------------:|:-----------------------------------------------:|
|      Android/JVM       |             SQLite via SQLDelight               |
| iOS/macOS/tvOS/watchOS |     SQLite via SQLDelight using `-lsqlite3`      |
|         NodeJS         |                    In-memory                    |
|       BrowserJS        | IndexedDB (SQLite-wasm upcoming)                |
|    Linux x64/arm64     |     SQLite via SQLDelight using `-lsqlite3`      |

An in-memory `Persistence` is available on every platform.

### Runtime Dependencies

DitchOoM Kotlin Multiplatform runtime dependencies:

* [Buffer](https://github.com/DitchOoM/buffer) — native buffer allocation/management, the `Codec` framework, and the `buffer-flow` byte layer.
* [Socket](https://github.com/DitchOoM/socket) — TCP (and QUIC / WebTransport) transports for an MQTT broker.
* [websocket](https://github.com/DitchOoM/websocket) — WebSocket transport (with TLS and permessage-deflate compression).

Official Kotlin Multiplatform runtime dependencies:

* [kotlinx-coroutines](https://github.com/Kotlin/kotlinx.coroutines) — coroutines with multiplatform support.

Other runtime dependencies:

* [SQLDelight](https://github.com/cashapp/sqldelight) — typesafe Kotlin APIs for SQLite persistence.

### [Supported Platforms](https://kotlinlang.org/docs/multiplatform.html)

| Platform  | MQTT 3.1.1 (v4) | MQTT 5.0 | LWT | SSL / TLS | Persistence | Auto Reconnect | Offline Buffering | TCP | WebSocket | Coroutines API | High Availability |
|:---------:|:---------------:|:--------:|:---:|:---------:|:-----------:|:--------------:|:-----------------:|:---:|:---------:|:--------------:|:-----------------:|
|   `JVM`   |       🚀        |    🚀    | 🚀  |    🚀     |     🚀      |       🚀       |        🚀         | 🚀  |    🚀     |       🚀       |        🚀         |
| `Browser` |       🚀        |    🚀    | 🚀  |    🚀     |     🚀      |       🚀       |        🚀         | ⛔  |    🚀     |       🚀       |        🚀         |
| `Node.JS` |       🚀        |    🚀    | 🚀  |    🚀     |     📝      |       🚀       |        🚀         | 🚀  |    🚀     |       🚀       |        🚀         |
| `Android` |       🚀        |    🚀    | 🚀  |    🚀     |     🚀      |       🚀       |        🚀         | 🚀  |    🚀     |       🚀       |        🚀         |
|   `iOS`   |       🚀        |    🚀    | 🚀  |    🚀     |     🚀      |       🚀       |        🚀         | 🚀  |    🚀     |       🚀       |        🚀         |
|  `macOS`  |       🚀        |    🚀    | 🚀  |    🚀     |     🚀      |       🚀       |        🚀         | 🚀  |    🚀     |       🚀       |        🚀         |
| `watchOS` |       🚀        |    🚀    | 🚀  |    🚀     |     🚀      |       🚀       |        🚀         | 🚀  |    🚀     |       🚀       |        🚀         |
|  `tvOS`   |       🚀        |    🚀    | 🚀  |    🚀     |     🚀      |       🚀       |        🚀         | 🚀  |    🚀     |       🚀       |        🚀         |
|  `Linux`  |       🚀        |    🚀    | 🚀  |    🚀     |     🚀      |       🚀       |        🚀         | 🚀  |    🚀     |       🚀       |        🚀         |

> 🚀 = Ready &nbsp; 📝 = Planned &nbsp; ⛔ = Unavailable (platform API limits)

## Installation

Published to Maven Central under `com.ditchoom`.

```kotlin
dependencies {
    // The client (pulls in buffer + socket transitively).
    implementation("com.ditchoom:mqtt-client:<version>")

    // Add the protocol version(s) you need — or both:
    implementation("com.ditchoom:mqtt-4-models:<version>") // MQTT 3.1.1 (v4)
    implementation("com.ditchoom:mqtt-5-models:<version>") // MQTT 5.0
}
```

Find the latest version on [Maven Central](https://search.maven.org/artifact/com.ditchoom/mqtt-client).

## Usage

The client is **in-process**: you create a `Persistence`, register a broker, and start an `MqttClient`.
It connects, keeps itself connected (automatic reconnection + failover across the broker's connection
options), and persists in-flight QoS 1/2 messages.

```kotlin
import com.ditchoom.mqtt.client.MqttClient
import com.ditchoom.mqtt.connection.MqttConnectionOptions
import com.ditchoom.mqtt.controlpacket.QualityOfService
import com.ditchoom.mqtt.controlpacket.TopicFilter
import com.ditchoom.mqtt.controlpacket.OpaquePublishPayloadCodec
import com.ditchoom.mqtt3.controlpacket.ConnectionRequest // v4; use com.ditchoom.mqtt5.* for v5
import com.ditchoom.buffer.Charset
import com.ditchoom.buffer.toReadBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.take

val scope = CoroutineScope(Dispatchers.Default)

// 1. Describe how/where to connect. A broker may list several options for high-availability failover.
val connection = MqttConnectionOptions.SocketConnection(host = "test.mosquitto.org", port = 1883)
val connectionRequest = ConnectionRequest(clientId = "my-client", keepAliveSeconds = 60, cleanSession = true)

// 2. Choose persistence (in-memory here) and register the broker.
val persistence = connectionRequest.controlPacketFactory.defaultPersistence(inMemory = true)
val broker = persistence.addBroker(connection, connectionRequest)

// 3. Start the client (stays connected until you shut it down).
val client = MqttClient.start(scope = scope, broker = broker, persistence = persistence)
client.awaitConnectivity()

// 4. Observe messages on a topic filter. A Codec<P> decodes the payload; OpaquePublishPayloadCodec
//    hands you the raw bytes. Register the observer before/at subscribe time.
val messages = client.observe(TopicFilter.fromOrThrow("test/+"), OpaquePublishPayloadCodec)
scope.launch {
    messages.take(1).collect { (publish, payload) ->
        val text = payload.handle.asReadBuffer().let { it.readString(it.remaining(), Charset.UTF8) }
        println("Received on ${publish.topic}: $text")
    }
}

// 5. Subscribe, then publish.
client.subscribe("test/+", OpaquePublishPayloadCodec, QualityOfService.AT_LEAST_ONCE).subAck.await()
client.publish(
    topicName = "test/123",
    qos = QualityOfService.EXACTLY_ONCE,
    payload = "hello".toReadBuffer(Charset.UTF8),
)

// 6. Clean up (drain = true waits for in-flight QoS 1/2 acks first).
client.unsubscribe("test/+").unsubAck.await()
client.shutdown(sendDisconnect = true, drain = true)
```

### Typed payloads

`publish`, `subscribe`, and `observe` are generic over a payload type `P` and take a `Codec<P>`:

```kotlin
// Untyped / raw bytes:
client.publish(topicName = "t", payload = bytes /* ReadBuffer */, qos = QualityOfService.AT_LEAST_ONCE)

// Typed — supply a Codec<P> (hand-written or KSP-generated via @ProtocolMessage):
client.publish(topic = "t", payload = myValue, payloadCodec = MyCodec, qos = QualityOfService.AT_LEAST_ONCE)
client.observe(TopicFilter.fromOrThrow("t"), MyCodec).collect { (publish, value) -> /* value: P */ }
```

`OpaquePublishPayloadCodec` is the built-in codec for raw application bytes. See the
[typed payloads guide][docs] for defining your own.

## Transports

All transports produce the same `Connection<ControlPacket>` and are selected behind a composable
`MqttTransport` seam, so the rest of the client is transport-agnostic. Pick a transport by the
`MqttConnectionOptions` subtype (or plug in your own via a custom resolver / `connectSingle`):

| Option | Transport | Status |
|--------|-----------|--------|
| `SocketConnection` | TCP (+ TLS) | ✅ Ready |
| `WebSocketConnectionOptions` | WebSocket (+ TLS, permessage-deflate) | ✅ Ready |
| `QuicConnectionOptions` | QUIC (native) | 🧪 Experimental — implemented (single stream), not broker-tested. Non-standard (EMQX-style); native only. |
| `WebTransportConnectionOptions` | WebTransport (incl. browser) | 🧪 Experimental — implemented, not broker-tested. The web substitute for QUIC where UDP is unavailable. |

## Building Locally

- `git clone git@github.com:DitchOoM/mqtt.git`
- Open the cloned directory with [IntelliJ IDEA](https://www.jetbrains.com/idea/download) ([import as a Gradle project](https://www.jetbrains.com/help/idea/gradle.html#gradle_import_project_start)).

### Build Commands

```bash
# Build & test all platforms
./gradlew allTests

# Run specific platform tests
./gradlew jvmTest                 # JVM tests
./gradlew jsNodeTest              # Node.js tests
./gradlew jsBrowserTest           # Browser tests
./gradlew testDebugUnitTest       # Android unit tests
./gradlew linuxX64Test            # Linux native tests
./gradlew macosArm64Test          # macOS tests (requires macOS)
./gradlew iosSimulatorArm64Test   # iOS tests (requires macOS)

# Linting
./gradlew ktlintCheck             # Check code style
./gradlew ktlintFormat            # Auto-format code

# Wire-format snapshot gate (fails on breaking MQTT wire-format drift)
./gradlew checkCodecSchema        # Verify against committed baseline
./gradlew updateCodecSchema       # Accept an intentional wire change

# Publish to local maven
./gradlew publishToMavenLocal

# Get next version
./gradlew -q :mqtt-client:nextVersion
```

## Roadmap

See the [open issues](https://github.com/DitchOoM/mqtt/issues) for proposed features and known issues,
and `TODO.md` for tracked follow-ups (QUIC/WebTransport implementation).

## Contributing

Contributions are what make the open-source community such an amazing place to learn, inspire, and
create. Any contributions you make are **greatly appreciated**.

1. Fork the Project
2. Create your Feature Branch (`git checkout -b feature/AmazingFeature`)
3. Commit your Changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the Branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request

## License

Distributed under the MIT License. See [`LICENSE`](LICENSE) for more information.

[docs]: https://ditchoom.github.io/mqtt/

[contributors-shield]: https://img.shields.io/github/contributors/DitchOoM/mqtt.svg?style=for-the-badge

[contributors-url]: https://github.com/DitchOoM/mqtt/graphs/contributors

[forks-shield]: https://img.shields.io/github/forks/DitchOoM/mqtt.svg?style=for-the-badge

[forks-url]: https://github.com/DitchOoM/mqtt/network/members

[stars-shield]: https://img.shields.io/github/stars/DitchOoM/mqtt.svg?style=for-the-badge

[stars-url]: https://github.com/DitchOoM/mqtt/stargazers

[issues-shield]: https://img.shields.io/github/issues/DitchOoM/mqtt.svg?style=for-the-badge

[issues-url]: https://github.com/DitchOoM/mqtt/issues

[license-shield]: https://img.shields.io/github/license/DitchOoM/mqtt.svg?style=for-the-badge

[license-url]: https://github.com/DitchOoM/mqtt/blob/main/LICENSE

[linkedin-shield]: https://img.shields.io/badge/-LinkedIn-black.svg?style=for-the-badge&logo=linkedin&colorB=555

[linkedin-url]: https://www.linkedin.com/in/thebehera
