# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

MQTT is a Kotlin Multiplatform library providing MQTT 3.1.1 (v4) and MQTT 5.0 client implementations with automatic reconnection, message persistence, and offline buffering. It delegates to native platform APIs via the DitchOoM buffer and socket libraries, and runs over any socket `Transport` (TCP, TLS, WebSocket, QUIC, WebTransport).

**Package:** `com.ditchoom.mqtt`

## No ByteArray in Production Code

Production source sets (`*Main/`) must not allocate or accept `kotlin.ByteArray`. Control-packet decode, payload dispatch, and persistence flows all run at message volume; a single missed `ByteArray` in a hot path is a guaranteed copy per packet. Use `ReadBuffer` / `WriteBuffer` (or the typed `Codec<P>` surface — `MqttClient.publish<P>` / `subscribe<P>` / `observe<P>` each take a `Codec<P>`, and CONNECT carries `OpaquePublishPayload` / `OwnedBytesHandle` for Will and Password slots).

**Platform boundaries** where `ByteArray` is genuinely unavoidable:

- **SQLDelight BLOB binding** — JDBC `PreparedStatement.setBytes(int, byte[])` and the K/N SQLite driver's `bind_blob` both take a `ByteArray`. Sites: `SqlDatabasePersistence` (v4 + v5) for Will payload, auth data, correlation data. A custom `ColumnAdapter<ReadBuffer, ByteArray>` would consolidate the six call-site copies into a single adapter — tracked for Phase 4.
- **IndexedDB on JS** — `Int8Array` keys, reached via `ByteArray.unsafeCast<Int8Array>()`. Zero-copy when the source buffer is `JsBuffer`-backed.
- **Kotlin stdlib `Base64`** — takes / returns `ByteArray`. Used by the MQTT v5 AUTH flow.

For each, annotate the call site with `@Suppress("NoByteArrayInProd")` and a one-line inline comment naming the specific driver / API. Tests (`*Test/`) may use `ByteArray` freely.

### No raw bytes inside `Payload` either (buffer-v1 lockdown)

The buffer-v1 lockdown extends the rule transitively into `Payload`-implementing types: no `ReadBuffer`, `WriteBuffer`, `PlatformBuffer`, `kotlin.ByteArray`, or primitive arrays as fields on a class that implements `com.ditchoom.buffer.codec.Payload`. The processor's `walkType` walks Payload types recursively and rejects forbidden fields at KSP-time. The previous `BufferPayload(val buffer: ReadBuffer) : Payload` shape is removed.

The three canonical decode patterns (see `buffer/CLAUDE.md` §"Canonical decode patterns"):
1. **Zero-copy typed value** — `Bitmap(val nativeBitmap: PlatformBitmap) : Payload`. Walker stops at `PlatformBitmap` (expect class, not a Payload, not a value class).
2. **Consumer-owned `PlatformBuffer`** — `data class IpcBuffer(val buffer: PlatformBuffer)` — NOT `Payload`. Decode allocates via `DecodeContext[BufferFactoryKey]` and copies.
3. **Consumer-owned `ByteArray`** — `data class OpaqueBytes(val bytes: ByteArray)` — NOT `Payload`. Decode via `buffer.copyToByteArray(n)`.

**Wire-typed bytes carriers** (the four MQTT binary slots): all reshaped to the framework's canonical owned-bytes carriers — `OpaquePublishPayload : Payload` for PUBLISH application bytes + Will payload (admissible into `<P : Payload>`), and `OwnedBytesHandle` (not Payload) for CONNECT Password, CorrelationData (v5 §3.3.2.3.6 / §3.1.3.2.4), and AuthenticationData (v5 §3.1.2.11.10 / §3.15.2.2.2 / §3.2.2.3.18). Bytes-exact; the spec-compliant `<P : Payload>` constraint prevents accidentally publishing a password.

## Build Commands

```bash
# Build & test all platforms
./gradlew allTests

# Run specific platform tests
./gradlew jvmTest                 # JVM tests
./gradlew jsNodeTest              # Node.js tests
./gradlew jsBrowserTest           # Browser tests
./gradlew testDebugUnitTest       # Android unit tests
./gradlew macosArm64Test          # macOS tests (requires macOS)
./gradlew iosSimulatorArm64Test   # iOS tests (requires macOS)

# Fuzz tests (opt-in; excluded from default test runs — see .github/workflows/fuzz.yaml)
./gradlew jvmTest jsNodeTest linuxX64Test -PfuzzTests            # deterministic fuzzers (models-base/v4/v5)
JAZZER_FUZZ=1 ./gradlew :models-v4:jvmTest -PfuzzTests --tests '*JazzerFuzzTest'  # coverage-guided (JVM)

# Conformance tests (opt-in; need the paho.mqtt.testing broker on localhost:1883 —
# see .github/workflows/conformance.yaml for the pinned SHA + start command)
./gradlew :mqtt-client:jvmTest -PconformanceTests

# Real-broker interop (opt-in; start a broker with .github/interop/start-broker.sh and set
# MQTT_INTEROP_BROKER / _PROTOCOL / _TRANSPORT / _PORT / _WS_PATH — see InteropTarget)
./gradlew :mqtt-client:jvmTest -PinteropTests --tests '*BrokerInteropTest*'

# Public ABI gate (macOS only: only a Mac declares the full klib target set)
./gradlew checkKotlinAbi
./gradlew updateKotlinAbi         # after an intentional API change; commit */api/*

# Until websocket 2.1.0 is on Maven Central (see "websocket 2.1.0 placeholder" below)
./gradlew build -PditchoomWebsocketVersion=2.1.0-SNAPSHOT

# Linting
./gradlew ktlintCheck             # Check code style
./gradlew ktlintFormat            # Auto-format code

# Publish to local maven
./gradlew publishToMavenLocal

# Get next version
./gradlew -q :mqtt-client:nextVersion
```

## Architecture

### Module Structure

```
mqtt/
├── models-base/     # Shared MQTT protocol interfaces (IConnectionRequest, IPublishMessage, etc.)
├── models-v4/       # MQTT 3.1.1 (v4) control packet implementations + SQLDelight persistence
├── models-v5/       # MQTT 5.0 control packet implementations + SQLDelight persistence
└── mqtt-client/     # Client logic: MqttClient, connection management, persistence wiring
```

### Kotlin Multiplatform Structure

Each module uses the expect/actual pattern with platform-specific implementations:

```
src/
├── commonMain/          # Shared interfaces and logic
├── commonTest/          # Shared tests run on all platforms
├── jvmMain/             # JVM-specific implementations
├── androidMain/         # Android: in-process client (no AIDL service; flat MqttClient)
├── appleMain/           # iOS/macOS/watchOS/tvOS implementations
├── jsMain/              # Browser/Node.js: in-process client
├── linuxMain/           # Linux x64/arm64 implementations
└── wasmJsMain/          # WASM/JS implementations (models-v4, models-v5)
```

### Key Components

- **`MqttClient`** - In-process client API for publish/subscribe/unsubscribe with typed `Codec<P>`. Handles reconnection automatically. Started with `MqttClient.start(scope, broker, persistence, endpoint, failover = emptyList(), bufferFactory, inFlightLimit, timeSource)`; each pass dials `endpoint`, then `failover` in order.
- **`MqttEndpoint`** - `MqttEndpoint(host, port, transport: Transport = TcpTransport(), config: TransportConfig = TransportConfig.LOW_LATENCY, connackTimeout = 15.seconds)`. How to reach a broker; passed to `start`, never persisted.
- **`MqttBroker`** - `MqttBroker(identifier, connectionRequest)`: the session identity persistence keys state by. Created with `persistence.addBroker(connectionRequest)`.
- **`ControlPacket`** - Base class for all MQTT packets. Factory pattern via `ControlPacketFactory` / `ControlPacketV4Factory` / `ControlPacketV5Factory`.
- **`Persistence`** - SQLDelight-backed message persistence (JVM, Android, Apple, Linux).

### Dependencies

- **buffer** (`com.ditchoom:buffer` **6.30.8**, + `buffer-codec` / `buffer-flow`) - Native byte buffer management. `buffer-flow` provides the `ByteSource`/`ByteSink`/`ByteStream` types with `ReadPolicy`/`WritePolicy` and `ReadResult.End`/`Reset`.
- **socket** (`com.ditchoom:socket` **4.24.0**) - **`api`** dependency of `mqtt-client`, so consumers see `Transport`, `TcpTransport`, `FallbackTransport`, `MemoryTransport`, `TransportConfig` and `TlsConfig`. One immutable `TransportConfig` per dial; a connection **is** a `ByteStream`.
- **Transport artifacts (not dependencies of `mqtt-client`; consumers add the ones they use):** `com.ditchoom:websocket-tcp` **2.1.0** (`WebSocketTransport`; JVM, Android, Node, Apple, linuxX64 — no browser), `com.ditchoom:socket-quic-default` (`QuicTransport`; experimental, non-standard but EMQX implements it, native only — JS/wasm have no raw UDP, no tvOS/watchOS engine), `com.ditchoom:socket-webtransport` (`WebTransportTransport`; experimental, all targets incl. browser). `websocket-tcp` is a `commonTest` dependency only.
- **kotlinx-coroutines** - Async/coroutines support
- **sqldelight** - SQLite persistence for models-v4 and models-v5

**Transport seam.** The seam is socket's `com.ditchoom.socket.transport.Transport`, carried on `MqttEndpoint.transport`. There is no MQTT-specific transport type. `EndpointConnector` (internal, `mqtt-client/.../net/MqttConnector.kt`) dials the endpoint with `CodecConnection.connect(...)` and frames the byte stream with the internal `MqttCodec`, so TCP, TLS (`config.tls`), WebSocket, QUIC, WebTransport, `FallbackTransport`, `MemoryTransport` and custom transports all go through one path. It always overrides `config.readPolicy` with `ReadPolicy.UntilClosed` (idle sessions must not time out; keep-alive owns liveness). `config.connectTimeout` bounds the transport handshake; `MqttEndpoint.connackTimeout` bounds the CONNACK wait in `ConnectivityManager`, which also owns failover across `endpoint` + `failover`. Tests substitute the internal `MqttConnector` fun interface to script a broker packet by packet. Caveat: a QUIC stream takes its read policy from `QuicOptions.persistentStreams`, not from `TransportConfig`, so QUIC endpoints need `persistentStreams = true`.

**Send quota.** QoS 1/2 `publish` suspends until a session exists and the outbound window has room. The quota is min(`InFlightLimit`, CONNACK Receive Maximum), recomputed on every connection (`OutboundWindow`). `PublishResult.QoS1/QoS2.packetId` is a `PacketId` value class; `QoS1State`/`QoS2State` end in `Acknowledged`/`Complete` or `SessionDiscarded` (broker started a fresh session; the message is not resent).

**Codec wire-format gate.** `models-base`/`models-v4`/`models-v5` apply the `com.ditchoom.buffer.codec-schema` plugin (`failOnBreaking = true`). `checkCodecSchema` (wired into `check`) diffs the KSP-emitted descriptor against the committed `src/codecSchema/codec-schema.txt` baseline; MQTT's wire format is spec-fixed so breaking drift fails the build. After an *intentional* wire change: `./gradlew updateCodecSchema` then commit the baseline.

### Build System

- **Gradle 9.9.0** with Kotlin 2.4.21 and AGP 9.5.1, on AGP's transitional classic DSL (`android.newDsl=false`, `android.builtInKotlin=false` in `gradle.properties`)
- **JDK 21** toolchain (`jvmToolchain(21)`); Android `compileSdk = 37`, `minSdk = 23`
- **Version catalog** at `gradle/libs.versions.toml`
- **Shared version management** via `gradle/setup.gradle.kts` (reads latest version from Maven Central)
- **Publishing** via vanniktech/maven-publish plugin to Maven Central Portal
- **Split CI** - Linux builds JVM/JS/Android targets, macOS builds Apple targets
- **`injectAppleVariantsIntoModuleMetadata()`** in each module handles split-CI metadata merging

## CI/CD

- PR review (`review.yaml`) triggers `build-linux` + `build-apple` + `api-check` + `platforms` (arm64 Linux, x86_64 macOS) + `validate-artifacts`
- **ABI gate** (`api-check.yaml`, part of `review.yaml`): `./gradlew checkKotlinAbi` compares every published module's JVM, Android and klib ABI with the dumps committed under each module's `api/`. After an intentional public API change run `./gradlew updateKotlinAbi` **on macOS** (only a Mac declares the full klib target set) and commit the `api/` files.
- **Broker interop** (`interop.yaml`, on every PR, separate from `review.yaml`): matrix of {mosquitto, EMQX, HiveMQ} x {MQTT 3.1.1, 5} x {tcp, tls, ws}. Each cell starts a broker with `.github/interop/start-broker.sh` and runs `BrokerInteropTest` with `./gradlew :mqtt-client:jvmTest -PinteropTests`. Brokers advertise Receive Maximum 10 so the send quota is exercised.
- **Nightly** (`nightly.yaml`, daily): `SendQuotaTest` repeated 20x on `jvmTest` and `linuxX64Test`, the interop matrix with the stress test repeated per cell (`iterations`), and `fuzz.yaml`.
- **Downstream canary** (`canary.yaml`, daily, on dispatch, and on PRs touching it or `settings.gradle.kts`): builds buffer, socket and websocket `main` into `mavenLocal` as snapshots and builds/tests mqtt against them.
- **Snapshot overrides** (`settings.gradle.kts`): `-PditchoomBufferVersion=`, `-PditchoomSocketVersion=`, `-PditchoomWebsocketVersion=` (or `ORG_GRADLE_PROJECT_*`) replace the catalog versions without editing `gradle/libs.versions.toml`; the buffer override also adds `mavenLocal()` to plugin resolution for the codec-schema plugin. Releases never set them.
- **websocket 2.1.0 placeholder (temporary):** the catalog pins `websocket = "2.1.0"`, which is not on Maven Central yet. CI jobs run `.github/actions/websocket-preview` first, which builds the DitchOoM/websocket 2.1.0 branch into `mavenLocal` as `2.1.0-SNAPSHOT` and points later Gradle calls at it. Local builds do the same by hand and pass `-PditchoomWebsocketVersion=2.1.0-SNAPSHOT`. Delete the action and its `uses:` lines once 2.1.0 ships.
- **Optional (non-required) PR checks**: `fuzz.yaml` (deterministic fuzzers on every PR; Jazzer coverage-guided fuzzing weekly/on-dispatch/nightly) and `conformance.yaml` (paho.mqtt.testing conformance broker + spec-statement coverage report). Neither is referenced by `review.yaml`, so they never gate merges.
- PR merge to main triggers full build + publish to Maven Central
- PR labels control version bumping: `major`, `minor`, or patch (default)
- `skip-release` label skips publishing; `draft-release` publishes to staging only
