# mqtt — open follow-ups

Tracked here so the gaps from today's session (2026-05-14) aren't lost.

## Today's validation pass against new buffer + socket + websocket artifacts

- [x] `./gradlew check` against `buffer 4.3.0-SNAPSHOT`, `socket 3.0.1-SNAPSHOT`, `websocket 1.1.2-SNAPSHOT` → **green**. Two test fixes needed along the way (committed in the same pass): `MqttClientCodecRoutingRegressionTest` had `kotlinx.coroutines.runBlocking` (doesn't exist on JS) and then needed `runTestNoTimeSkipping` because the test drives real `Dispatchers.Default` work with `withTimeout` — `runTest`'s virtual time triggers the timeout immediately. Final tally: models-base 43/43/43/43/43, models-v4 113×5, models-v5 313×5, mqtt-client 64/41/41/41/39 across jvm / jsNode / linuxX64 / testDebug / testRelease — 0 failures everywhere.

## Pre-existing follow-ups (carried in from prior sessions / memories)

- [ ] **Android instrumented timeouts.** `[[mqtt_client_integration_test_flakiness]]` notes ~16 timeouts under `-PintegrationTests` on `connectedDebugAndroidTest`. Mirrored filter landed in `59bd2c44`, but the underlying root cause (MqttClient suspending more than the test budget) is unfixed.
- [ ] **`SqlDatabasePersistence` `ColumnAdapter<ReadBuffer, ByteArray>`.** CLAUDE.md flags six `setBytes` call sites (Will payload, AUTH data, correlation data on v4 + v5) as platform-boundary `ByteArray` copies. Consolidating into a single adapter is tracked for "Phase 4."
- [ ] **`:benchmark` module — reincarnation from scratch.** Deleted in `2de1ddab` (Phase B removed the APIs it depended on); a new benchmark module against the v2 Codec API hasn't been built yet ([[benchmark_module_phase_b_followup]]).

## Possible work surfaced today

- [ ] **Validate persistent-zlib end-to-end through WebSocket transport.** mqtt-over-websockets uses the persistent zlib path now that we routed it via `permessage-deflate`. A focused integration test that exchanges large MQTT PUBLISHes (≥ 32 KB application bytes) over a WebSocket transport with `client_no_context_takeover` would surface any boundary issues that don't show up in the websocket repo's Autobahn cases.
