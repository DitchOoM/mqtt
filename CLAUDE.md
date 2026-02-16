# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

MQTT is a Kotlin Multiplatform library providing MQTT 3.1.1 (v4) and MQTT 5.0 client implementations with automatic reconnection, message persistence, offline buffering, and IPC support. It delegates to native platform APIs via the DitchOoM buffer, socket, and websocket libraries.

**Package:** `com.ditchoom.mqtt`

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
└── mqtt-client/     # Client logic: MqttService, MqttClient, connection management, IPC
```

### Kotlin Multiplatform Structure

Each module uses the expect/actual pattern with platform-specific implementations:

```
src/
├── commonMain/          # Shared interfaces and logic
├── commonTest/          # Shared tests run on all platforms
├── jvmMain/             # JVM-specific implementations
├── androidMain/         # Android: AIDL IPC, ContentProvider, AndroidX Startup
├── appleMain/           # iOS/macOS/watchOS/tvOS implementations
├── jsMain/              # Browser/Node.js: Web Worker IPC
├── linuxMain/           # Linux x64/arm64 implementations
└── wasmJsMain/          # WASM/JS implementations (models-v4, models-v5)
```

### Key Components

- **`MqttService`** - Entry point. Manages brokers, clients, persistence. Supports IPC-backed remote service.
- **`MqttClient`** - Client API for publish/subscribe/unsubscribe. Handles reconnection automatically.
- **`LocalMqttService`** / **`LocalMqttClient`** - In-process implementations.
- **`ControlPacket`** - Base class for all MQTT packets. Factory pattern via `ControlPacketFactory`.
- **`Persistence`** - SQLDelight-backed message persistence (JVM, Android, Apple, Linux).
- **IPC** (`mqtt-client/src/commonMain/.../ipc/`) - Cross-process communication (Android AIDL, JS Web Workers).

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
