# MQTT follow-ups handoff (decode-path hardening + concurrent-publish corruption)

**Written:** 2026-07-04 · **Branch:** `feature/v2-api-cleanup`
**Context:** the fuzz + paho-conformance infra (still uncommitted on this branch) surfaced two
client defects, filed as DitchOoM/mqtt#12 and #13. This file captures the *root-cause
analysis + agreed plan* so the fix work can run in a clean session with full context budget.

Two independent workstreams. Do them in either order; they don't touch the same files.

---

## Workstream 1 — Decode-path hardening (reframes mqtt#13)

### The finding that reframes the issue

`ControlPacketV4.from()` / `ControlPacketV5.from()` have **zero production callers**. All ~150
call sites are in `*Test`. The real production wire-decode path is:

```
ConnectivityManager.kt:106   conn.receive().collect { packet -> readChannel.emit(packet) }
   → buffer-flow framing → MqttCodec.decode (mqtt-client/.../MqttCodec.kt:49-77)
   → ControlPacketV4Codec.decodeAggregating(...)   (generated, signature below)
```

- `from()` is **public `commonMain` API that only tests use**, and hardcodes `OpaquePublishPayload`.
  It's already half-duplicated in `commonTest` by `decodeV4(buffer)` / `decodeV5(buffer)` in
  `TestDecodeHelpers.kt` (same codec call, minus the `DecodeException → MalformedPacketException` remap).
- **`MqttCodec.decode` has no try/catch.** `decodeAggregating` throws raw `DecodeException` /
  `BufferUnderflowException` / `IllegalArgumentException` / charset exceptions, which propagate to
  the reconnect loop as a generic connection failure. So the "malformed → `MalformedPacketException`"
  contract is a **test-only fiction living in `from()`**. Production never emits it.
- Consequently the new fuzzers (which call `ControlPacketVx.from(...)`) harden a path **production
  does not run**. That is the actual "bad design" — not that `from()` exists, but that our
  robustness tests point at the wrong boundary.

### Agreed plan: **production-path first (B), then cleanup (A)**

**Step B — point the hardening at the real boundary (the substantive win):**

1. Add a production-mirroring decode helper to each fuzz-support file (`FuzzSupport.kt`, maintained
   in triplicate — models-base/v4/v5). It must mirror `MqttCodec.decode`, i.e. use
   `decodeAggregating` with the PUBLISH topic-router returning `OpaquePublishPayloadCodec`:

   ```kotlin
   // v5 example
   fun decodeProductionV5(bytes: ByteArray): ControlPacketV5<OpaquePublishPayload> =
       ControlPacketV5Codec.decodeAggregating<OpaquePublishPayload>(
           buffer = bytesToReadBuffer(bytes),
           context = DecodeContext.Empty,
           onPublish = { it.complete(OpaquePublishPayloadCodec) },  // == MqttCodec.decode router
       )
   // v4 twin uses onPublishMessageV4 = { it.complete(OpaquePublishPayloadCodec) }
   ```
   Generated signatures to match:
   - `ControlPacketV4Codec.decodeAggregating<P>(buffer, context, onPublishMessageV4 = {...})`
     — `models-v4/build/generated/ksp/metadata/commonMain/.../ControlPacketV4Codec.kt:97`
   - `ControlPacketV5Codec.decodeAggregating<P>(buffer, context, onPublish = {...})`
     — `models-v5/build/generated/.../ControlPacketV5Codec.kt:98`

2. Repoint the fuzzer call sites from `ControlPacketVx.from(...)` to `decodeProductionVx(...)`:
   - `models-v4/.../fuzz/ControlPacketV4FuzzTest.kt:28,41`
   - `models-v5/.../fuzz/ControlPacketV5FuzzTest.kt:28,41`
   - `models-v4/.../fuzz/ControlPacketV4JazzerFuzzTest.kt:22` (jvmTest)
   - `models-v5/.../fuzz/ControlPacketV5JazzerFuzzTest.kt:22` (jvmTest)
   - (models-base `RemainingLengthFuzzTest` already targets `MqttRemainingLengthCodec` directly — leave it.)

3. **Accept-set stays honest for the production path.** Since production does NOT wrap to
   `MalformedPacketException`, `isAcceptedDecodeFailure` should keep accepting the raw families
   (`DecodeException`, `MqttException`, `BufferUnderflowException`/`IndexOutOfBounds`/`ArrayIndexOutOfBounds`,
   `IllegalArgumentException`, JVM charset `MalformedInputException`/`UnmappableCharacterException`,
   plus `MissingCodecException` — the router can't hit it here since we always return a codec, but
   note it). Failure set (real bugs): `NullPointerException`, `IllegalStateException`,
   `ClassCastException`, hang, OOM. This is essentially the current set — repointing is mostly a
   call-site change, NOT a contract change.

4. **Optional production improvement (the proper home for mqtt#13's original intent):** decide
   whether `MqttCodec.decode` should wrap malformed wire bytes into `MalformedPacketException` so the
   reconnect loop can distinguish "broker sent garbage" from "socket died". If yes, wrap in
   `MqttCodec.decode` (mqtt-client), add a unit test, and the accept-set for the production fuzz path
   collapses to `MqttException`-only. This is a real behavior change — get a decision before doing it.

**Step A — delete `from()` (API hygiene, do after B):**

1. Delete `ControlPacketV4.from(buffer)` (`ControlPacketV4.kt:228-234`) and
   `ControlPacketV5.from(buffer)` (`ControlPacketV5.kt:200-208`) from `commonMain`.
2. Codemod the ~150 test call sites: `ControlPacketV4.from(` → `decodeV4(`, `ControlPacketV5.from(` → `decodeV5(`.
   - `decodeV4`/`decodeV5` already exist in `TestDecodeHelpers.kt` (both modules) and are `internal`.
   - **Caveat:** tests that assert `assertFailsWith<MalformedPacketException> { ...from(buf) }` (e.g.
     `V5PacketConnAckTests.kt:248`, `V5PacketConnectTests.kt:158/196/216`, `V5PacketAckShapedTests.kt:54/178`,
     `MalformedPacketTests.kt`, `V5PacketListShapedTests.kt:155/263`) rely on the remap. Either
     (a) give `decodeV4/V5` the same `try/catch → MalformedPacketException` wrapper `from()` had, or
     (b) change those assertions to the raw exception type. Prefer (a) to keep the diff mechanical.
3. Remove the now-stale KDoc references to `from()` in `TestDecodeHelpers.kt` and the fuzz-test headers.
4. Update the codec-schema baseline if `from()` removal changes any emitted descriptor (it shouldn't —
   `from()` is hand-written, not generated — but run `./gradlew checkCodecSchema` to confirm).

### Verify (Workstream 1)
```
./gradlew :models-base:jvmTest :models-v4:jvmTest :models-v5:jvmTest        # no props → unchanged counts
./gradlew -PfuzzTests :models-v4:jvmTest :models-v5:jvmTest :models-base:jvmTest  # fuzz green in seconds
./gradlew -PfuzzTests :models-v4:jsNodeTest :models-v4:linuxX64Test         # native/JS decode path
./gradlew ktlintCheck
```
Sanity: temporarily break the accept-set once and confirm a failure prints seed + hex.

---

## Workstream 2 — Concurrent QoS>0 wire corruption + Receive Maximum (mqtt#12)

### Root cause (leading hypothesis, from code inspection — needs broker confirmation)

**Unguarded packet-ID allocation** in
`models-base/src/commonMain/kotlin/com/ditchoom/mqtt/InMemoryPersistence.kt`:

```kotlin
21:  private var nextPacketId = 0.toUShort()          // no @Volatile / no Mutex
277: private fun getPacketId(): Int {                 // non-atomic read-modify-write
278:     nextPacketId++
279:     if (nextPacketId.toInt() == 0) nextPacketId++
282:     return nextPacketId.toInt()
283: }
```
`InMemoryPersistence.writePubGetPacketId` (suspend, line ~51-59) calls `getPacketId()` unguarded.
Two concurrent `MqttClient.publish(qos≥1)` → both can read the same `nextPacketId` → **duplicate
packet ID**. Same race at `writeUnsubGetPacketId` (~line 70) and
`writeSubUpdatePacketIdAndSimplifySubscriptions` (~line 146).

Also unprotected: `ControlPacketProcessor.qos1States` / `qos2States`
(`ControlPacketProcessor.kt:43,46`) — plain `mutableMapOf`, written from `MqttClient.publish`
(`MqttClient.kt:159,166`) and read/removed from `processIncomingMessages`.

### IMPORTANT — the symptom doesn't fully match the ID-race alone

The broker log shows a **packet-PARSE failure** ("`[MQTT-4.8.0-1] 'transient error' reading packet`"),
i.e. genuinely malformed *bytes* on the wire — not merely a duplicate-but-well-formed packet ID
(brokers usually PUBACK a duplicate ID without a parse error). So **also suspect the eager-encode /
shared-buffer path**: `writePubGetPacketId` stores `pub.maybeCopyWithNewPacketIdentifier(packetId)`
— if that mutates a shared/eager-encoded `WriteBuffer` in place rather than copying, two concurrent
callers interleave bytes → malformed frame. **Confirm with the paho proxy** (see below) which
mechanism actually corrupts the wire before committing to a fix.

### Fix sketch
1. Serialize packet-ID allocation + the dependent map writes. Options: a `Mutex` around the
   `getPacketId()` + `clientMessagesForBroker[id] = …` compound in `InMemoryPersistence` (methods are
   already `suspend`, so `Mutex.withLock` is clean), or an atomic counter. Guard the SUBSCRIBE/UNSUB
   paths too.
2. Make `qos1States`/`qos2States` access safe (confine to a single dispatcher, or lock).
3. Verify `maybeCopyWithNewPacketIdentifier` truly copies — no shared mutable buffer.
4. Implement [MQTT-3.3.4-9] **Receive Maximum send quota**: the paho broker advertises
   `Receive Maximum: 2` in CONNACK; the client currently ignores it. Add a client-side in-flight
   QoS>0 semaphore sized to the broker's Receive Maximum. (Masked by the corruption bug today.)

### Reproduce / verify
```
# Broker: clone eclipse-paho/paho.mqtt.testing @ 9d7bb80bb8b9d9cfc0b52f8cb4c1916401281103
python3 interoperability/startbroker.py                 # v4+v5 on :1883
# (optional) python3 interoperability/proxy.py           # parsed-packet capture to see the corrupt frame
./gradlew :mqtt-client:jvmTest -PconformanceTests --tests '*PahoConformanceV5Test' --rerun-tasks
```
Remove `@Ignore` on `PahoConformanceV5Test.concurrentQos1PublishesAllComplete`
(`PahoConformanceV5Test.kt:184`) — it currently reproduces the disconnect loop deterministically
with just 2 concurrent QoS1 publishes. Once fixed, also un-ignore &/or scale it up.

---

## Cross-cutting notes
- **Nothing on this branch is committed.** The entire fuzz/conformance infra is uncommitted working
  tree. Consider splitting: (i) the infra as its own PR, (ii) the mqtt#12 fix, (iii) the decode-path
  refactor — so fixes don't mix with new infra (the standing preference on this branch).
- After the decode-path refactor lands, update the filed issues: rewrite **mqtt#13** from "tighten
  `from()`" to "harden the production decode boundary (`MqttCodec.decode`/`decodeAggregating`) + repoint
  fuzzers; delete test-only `from()`". Comment on **mqtt#12** with the packet-ID-race + eager-encode
  root-cause analysis above.
- The `@Ignore` comments already cite the issue numbers (`UpstreamCodecBugRegressionTest.kt:32`,
  `PahoConformanceV5Test.kt:184`).

## Suggested opening prompt for the fresh session
> Read MQTT-FOLLOWUPS-HANDOFF.md at the repo root. Start with Workstream 1 Step B: add
> `decodeProductionV4/V5` helpers to the fuzz-support files and repoint the v4/v5/jazzer fuzzers at
> `decodeAggregating` (the real production decode path). Then Step A: delete `from()` and codemod the
> test sites to `decodeV4/V5`. Verify with the listed gradle commands. Do Workstream 2 (mqtt#12) after.
