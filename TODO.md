# mqtt — open follow-ups

Tracked here so the gaps from today's session (2026-05-14) aren't lost.

## buffer 6 + socket 3.6.x migration (2026-07-01)

Migrated the catalog to Maven Central releases: `buffer` `6.0.0` (+ `buffer-codec`/`buffer-flow`),
`socket` `3.6.10`. TCP path rewritten onto the new `TransportConfig` API; introduced a composable
`MqttTransport` seam (`TcpMqttTransport`, `WebSocketMqttTransport`, `QuicMqttTransport`,
`WebTransportMqttTransport` + `MqttTransportResolver`). Socket read is now `ReadPolicy.UntilClosed`
(persistent stream) with the CONNACK handshake bounded via `withTimeoutOrNull` in
`ConnectivityManager`. `models-base` `minSdk` bumped 19→21 (buffer 6 Android requires 21). Green:
JVM/JS/Android compile, JVM unit tests, ktlint, `checkCodecSchema`.

- [x] **WebSocket transport re-enabled** (2026-07-02). Bumped to `buffer 6.3.0` / `socket 3.8.3` /
      `websocket 2.0.2` (websocket now built on buffer 6). Restored the `WebSocketMqttTransport` wiring
      (`TcpTransport` `ByteStream` → `connectWebSocket(binaryCodec = MqttCodec)` → `mapNotNull`),
      re-added the `websocket` dep, restored `WebSocketConnectionAdapterTest`, and removed the `@Ignore`
      from `DefaultConnectionFactoryWsIntegrationTest`.
- [ ] **Implement QUIC transport** (`QuicMqttTransport`). Uncomment `socket-quic-default` +
      `socket-quic-quiche` deps; single-bidirectional-stream design is in the class KDoc. Native only.
- [ ] **Implement WebTransport transport** (`WebTransportMqttTransport`). Uncomment `socket-webtransport`;
      design in the class KDoc. Real on all targets incl. browser → the web substitute for QUIC.
- [ ] **Platform-aware default resolver.** Intended composition: route
      `QuicConnectionOptions` to QUIC natively but WebTransport on JS/wasmJs (no raw UDP). Belongs in a
      custom/expect-actual `MqttTransportResolver`, not `DefaultMqttTransportResolver`.
- [ ] **Note:** MQTT-over-QUIC is non-standard (EMQX extension) and MQTT-over-WebTransport is
      unspecified — both transports are experimental.

## CI parity pass (2026-07-01)

Aligned CI with the buffer/socket sibling workflows and closed test-coverage gaps.

Done:
- `linuxX64Test` added to `build-linux` (native code was built+published but never test-executed).
- `linuxArm64()` re-enabled across all modules (buffer 6 / socket 3.6.10 now publish arm64) — cross-compiled
  + published; this also fixes `validate-artifacts` which requires the `linuxarm64` metadata variant.
- Gradle build cache enabled (dropped `--no-build-cache` on the test steps + cache `~/.gradle/caches`);
  Konan cache key now includes `**/*.gradle.kts`.
- `review.yaml`: `concurrency.cancel-in-progress` + least-privilege `permissions: contents: read`.

Deferred (lower value for mqtt / higher setup cost — see buffer/socket for recipes):
- [ ] **linuxArm64 test execution** (QEMU like buffer, or a native `ubuntu-24.04-arm` runner like socket).
      Low marginal value here: mqtt's native code is generic Kotlin + SQLDelight (no arch-sensitive SIMD/SSL),
      and it's exercised by `linuxX64Test`; the arm64 test .kexe also needs an arm64 static libsqlite3 in the
      QEMU sysroot. Re-evaluate if arch-specific native bugs appear.
- [ ] **tvOS / watchOS test execution** — currently compile-only (macosArm64 + iosSimulatorArm64 cover the
      shared appleMain code). socket runs the full Apple simulator matrix on `macos-26`.
- [ ] **wasmJs target** — not declared in mqtt. Relevant if the WebTransport/web story extends to Wasm.
- [ ] **Pin GitHub Action versions to commit SHAs** (buffer/socket do this; supply-chain hardening).
- [ ] **Publish step still uses `--no-build-cache`** — could be made conditional (clean only for releases)
      like buffer to speed up PR runs.

## Today's validation pass against new buffer + socket + websocket artifacts

- [x] `./gradlew check` against `buffer 4.3.0-SNAPSHOT`, `socket 3.0.1-SNAPSHOT`, `websocket 1.1.2-SNAPSHOT` → **green**. Two test fixes needed along the way (committed in the same pass): `MqttClientCodecRoutingRegressionTest` had `kotlinx.coroutines.runBlocking` (doesn't exist on JS) and then needed `runTestNoTimeSkipping` because the test drives real `Dispatchers.Default` work with `withTimeout` — `runTest`'s virtual time triggers the timeout immediately. Final tally: models-base 43/43/43/43/43, models-v4 113×5, models-v5 313×5, mqtt-client 64/41/41/41/39 across jvm / jsNode / linuxX64 / testDebug / testRelease — 0 failures everywhere.

## Pre-existing follow-ups (carried in from prior sessions / memories)

- [ ] **Android instrumented timeouts.** `[[mqtt_client_integration_test_flakiness]]` notes ~16 timeouts under `-PintegrationTests` on `connectedDebugAndroidTest`. Mirrored filter landed in `59bd2c44`, but the underlying root cause (MqttClient suspending more than the test budget) is unfixed.
- [ ] **`SqlDatabasePersistence` `ColumnAdapter<ReadBuffer, ByteArray>`.** CLAUDE.md flags six `setBytes` call sites (Will payload, AUTH data, correlation data on v4 + v5) as platform-boundary `ByteArray` copies. Consolidating into a single adapter is tracked for "Phase 4."
- [ ] **`:benchmark` module — reincarnation from scratch.** Deleted in `2de1ddab` (Phase B removed the APIs it depended on); a new benchmark module against the v2 Codec API hasn't been built yet ([[benchmark_module_phase_b_followup]]).

## Possible work surfaced today

- [ ] **Validate persistent-zlib end-to-end through WebSocket transport.** mqtt-over-websockets uses the persistent zlib path now that we routed it via `permessage-deflate`. A focused integration test that exchanges large MQTT PUBLISHes (≥ 32 KB application bytes) over a WebSocket transport with `client_no_context_takeover` would surface any boundary issues that don't show up in the websocket repo's Autobahn cases.
