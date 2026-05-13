# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

MQTT is a Kotlin Multiplatform library providing MQTT 3.1.1 (v4) and MQTT 5.0 client implementations with automatic reconnection, message persistence, and offline buffering. It delegates to native platform APIs via the DitchOoM buffer, socket, and websocket libraries.

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

- **`MqttClient`** - In-process client API for publish/subscribe/unsubscribe with typed `Codec<P>`. Handles reconnection automatically.
- **`ControlPacket`** - Base class for all MQTT packets. Factory pattern via `ControlPacketFactory` / `ControlPacketV4Factory` / `ControlPacketV5Factory`.
- **`Persistence`** - SQLDelight-backed message persistence (JVM, Android, Apple, Linux).

### Dependencies

- **buffer** (`com.ditchoom:buffer`) - Native byte buffer management
- **socket** (`com.ditchoom:socket`) - TCP socket connections (via `mavenLocal()`)
- **websocket** (`com.ditchoom:websocket`) - WebSocket connections (via `mavenLocal()`)
- **kotlinx-coroutines** - Async/coroutines support
- **sqldelight** - SQLite persistence for models-v4 and models-v5

### Build System

- **Gradle 8.14.3** with Kotlin 2.3.0
- **Version catalog** at `gradle/libs.versions.toml`
- **Shared version management** via `gradle/setup.gradle.kts` (reads latest version from Maven Central)
- **Publishing** via vanniktech/maven-publish plugin to Maven Central Portal
- **Split CI** - Linux builds JVM/JS/Android targets, macOS builds Apple targets
- **`injectAppleVariantsIntoModuleMetadata()`** in each module handles split-CI metadata merging

## CI/CD

- PR review triggers `build-linux` + `build-apple` + `validate-artifacts`
- PR merge to main triggers full build + publish to Maven Central
- PR labels control version bumping: `major`, `minor`, or patch (default)
- `skip-release` label skips publishing; `draft-release` publishes to staging only
