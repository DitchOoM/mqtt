# PublishMessage Refactor — Handoff

This file documents the state of the `IPublishMessage<P>` → `PublishMessage` refactor on
`feature/v2-api-cleanup`. Read this if you're picking up the work in a fresh conversation.

## Plan reference

Full design: `~/.claude/plans/curried-painting-hamming.md`. Read that first — it describes
the *why* and the target API. This file is the implementation-state ledger.

## What was accomplished (committed as WIP)

The three `models-*` modules compile cleanly (main source sets, both JVM and JS targets).
The generic `IPublishMessage<P>` interface is gone; user-facing payload access is the scoped
`pub.usePayload { /* this: ReadBuffer */ }` lambda.

### models-base — COMPLETE
- `controlpacket/PublishMessage.kt` — **new**. Abstract class. Public fields: `topic`,
  `qualityOfService`, `dup`, `retain`, `packetIdentifier`. Public methods: `usePayload`,
  `payloadAsReadBufferOrNull` (for internal machinery), `payloadSize`, `invalidateScope`,
  `expectedResponse`, `setDupFlagNewPubMessage`, `maybeCopyWithNewPacketIdentifier`.
  `storage: PayloadStorage` is `protected` (subclasses in v4/v5 need access; `internal`
  doesn't cross module boundaries in KMP).
- `controlpacket/PayloadStorage.kt` — now inlined at the bottom of `PublishMessage.kt`.
  `sealed interface` with `Bytes(buffer: ReadBuffer?)` and `Encode(write, size)` variants.
  Public because subclasses access it, but never exposed on the public `PublishMessage` API.
- `Persistence.kt` — signatures now use `PublishMessage` instead of `IPublishMessage<*>` /
  `IPublishMessage<ReadBuffer?>?`. `IncomingPublishRecord.packet` is `PublishMessage`.
- `InMemoryPersistence.kt` — migrated.
- `ControlPacketFactory.kt` — `publish(...)` overloads return `PublishMessage`.
- `IPublishMessage.kt` — **deleted**.
- `IncomingPublish.kt` — **deleted**.

### models-v4 (common + js) — COMPLETE
- `controlpacket/PublishMessage.kt` — **rewritten**. Class is now `PublishMessageV4`
  (renamed from generic `PublishMessage<P>`). Extends base `PublishMessage` and implements
  `ControlPacketV4`. Hand-written encode/decode (bypasses `@ProtocolMessage` codec because
  the codec's `PayloadReader` callback forces an unwanted allocation — plan §"Wire decode").
  Backpatch + grow-on-overflow preserved for `PayloadStorage.Encode` typed-payload path.
  Factory helpers: `PublishMessageV4.Companion.ofRaw(...)`, `ofTyped(...)`, `from(...)`.
- `ControlPacketV4.kt` — dispatch now calls `PublishMessageV4.from(...)`.
- `ControlPacketV4Factory.kt` — both `publish(...)` overloads return `PublishMessage`
  (constructed via `PublishMessageV4.ofRaw` / `ofTyped`).
- `persistence/SqlDatabasePersistence.kt` — `persistIncomingPublish`, `writePubGetPacketId`,
  `getPubWithPacketId`, `incomingMessagesToRedispatch`, `messagesToSendOnReconnect`
  migrated. Uses `packet.payloadAsReadBufferOrNull()` to read bytes for SQLite BLOB storage.
  Constructs `PublishMessageV4.ofRaw(...)` on read-back.
- `jsMain/persistence/IDBObjects.kt` + `IDBPersistence.kt` — same migration pattern.

### models-v5 (common + js) — COMPLETE
- `controlpacket/PublishMessage.kt` — **rewritten**. Class is now `PublishMessageV5`.
  Carries `properties: Properties` field (the nested data class is preserved verbatim for
  structural API compatibility). Hand-written encode: length-prefixed topic + optional
  packetId + VBI-prefixed properties block + payload bytes. Encode uses the existing
  `encodeMqttProperty` / `mqttPropertiesSize` utilities. Decode uses `readProperties` then
  `Properties.from(...)` then a zero-copy slice for payload bytes.
  Factory helpers: `ofRaw`, `ofTyped`, `from`.
- `ControlPacketV5.kt` — dispatch calls `PublishMessageV5.from(...)`.
- `ControlPacketV5Factory.kt` — `publish(...)` overloads return `PublishMessage`.
- `persistence/SqlDatabasePersistence.kt` — migrated. Note: writes all v5 properties as
  separate SQLite columns; reads back and reconstructs `PublishMessageV5.Properties`.
- `jsMain/persistence/IDBObjects.kt` + `IDBPersistence.kt` — migrated.

## What remains (in priority order)

### 1. mqtt-client (commonMain) — DOES NOT COMPILE

Required edits (error messages from `./gradlew :mqtt-client:compileKotlinJvm` are specific
and actionable):

- **`PublishDispatcher.kt`** — uses `IncomingPublish<ReadBuffer?>` and `.payload` field.
  Rewrite to dispatch `PublishMessage` directly. After all handlers complete, call
  `pub.invalidateScope()`. Typed subscriber path: decode inside
  `pub.usePayload { with(decoder) { decode() } }` and pass both `pub` and decoded value
  to the handler.
- **`SubscriptionHandler.kt`** — `IncomingPublish<*>` → `PublishMessage`.
- **`ControlPacketProcessor.kt`** — `pub: IPublishMessage<*>` → `PublishMessage`.
  `pub.maybeCopyWithNewPacketIdentifier(...)` still works (method is on base class).
- **`MqttClient.kt`** — subscribe/observe/publish overloads. New signatures per plan §
  "Consumer API":
  - `suspend fun publish(pub: PublishMessage): PublishResult`
  - `subscribe(filter, handler: suspend (PublishMessage) -> Unit): Job`
  - `<P> subscribe(filter, decoder, handler: suspend (PublishMessage, P) -> Unit): Job`
  - `observe(filter): Flow<PublishMessage>` (emits materialized/owned-bytes copy before emit)
- **`LocalMqttClient.kt`** — thread through the new types.
- **`ipc/RemoteMqttClient.kt`** + **`RemoteMqttClientWorker.kt`** — same.
- **`IncomingPublishAdapter.kt`** — **delete**. The plan replaces it with direct use of
  `PublishMessage`. Tests that reference it (`IncomingPublishAdapterTest.kt`) should be
  deleted or rewritten.
- **`ScopedReadBuffer.kt`** — may be deleted. `PublishMessage.usePayload` already enforces
  scope via its `scopeInvalidated` flag check. If kept, callers should drop it.
- **`PayloadCodec.kt`** — verify no stale references.
- **`ControlPacketHelper.kt`**, **`ControlPacketOperation.kt`** — verify.

### 2. IPC (androidMain + jsMain) — not attempted
- `androidMain/.../ipc/AndroidRemoteMqttClient.kt` — AIDL parcel/unparcel of publishes.
  Will need to serialize the new `PublishMessage` across the IPC boundary: topic + qos +
  dup + retain + packetId + materialized payload bytes + (for v5) properties.
- `jsMain/.../ipc/JsRemoteMqttClient.kt` + `JsRemoteMqttClientWorker.kt` — Web Worker
  message serialization. Same shape as Android.

### 3. Tests — not attempted
All test files in v4/v5/mqtt-client that construct publishes do so via the old generic
`PublishMessage<ReadBuffer?>(...)` / `PublishMessage.buildPayload(...)` constructors, which
no longer exist.

Pattern to migrate:
```kotlin
// Old
PublishMessage(FixedHeader(...), VariableHeader(topic, packetId), payload)
// New
PublishMessageV4.ofRaw(topic = topic, qos = AT_LEAST_ONCE, payload = payload,
                      dup = false, retain = false, packetIdentifier = packetId)
```

Assertions on `.payload` content become `pub.usePayload { /* read bytes, assert */ }`.
Note the suspend requirement — wrap in `runBlocking { ... }` in JVM tests or use
`runTest`.

Files to migrate:
- **v4**: `PublishMessageTests.kt`, `SpecByteTests.kt`, `PersistenceTests.kt`
  (`ConnectionRequestTests.kt`, `SubscribeRequestTests.kt` may not need work — verify.)
- **v5**: `PublishMessageTests.kt`, `SpecByteTests.kt`, `PersistenceTests.kt`,
  and any other test that builds publishes.
- **mqtt-client**: `PublishDispatcherTest.kt` (has `v4Publish` helper on line ~30),
  `IncomingPublishAdapterTest.kt` (delete or rewrite), `ThroughputBenchmarkTest.kt`,
  `PooledToBufferLeakTest.kt`, `MqttClientTest.kt`, `PublicBrokerValidationTest.kt`,
  `FactoryComparisonBenchmark.kt`, `MemoryPressureTest.kt`.

### 4. New tests from plan §"Verification"

Not yet written. Plan calls for these:
- **Zero-copy regression test**: mutate the underlying network buffer after decode but
  before dispatcher runs; observe mutation via the `usePayload` receiver (proves slice
  shares storage).
- **Scope invalidation test**: leak the `ReadBuffer` receiver via `var leaked: ReadBuffer?`;
  assert post-invalidation `leaked!!.readByte()` throws.
- **User-allocated retention test**: inside `usePayload`, allocate own buffer and copy;
  after invalidation, verify owned copy still reads correctly.
- **Typed auto-ack test**: QoS 1/2 incoming with typed subscriber throws → no ack → redispatch.
- **Outgoing backpatch test**: `ThroughputBenchmarkTest` + `MemoryPressureTest` green
  (closure-based encode preserves zero-copy outgoing).

## Design decisions made (deviate slightly from plan)

1. **Base class is `abstract`, not `open`.** Plan showed `open class` but abstract is
   cleaner given v5 must override `encodeBody` / `remainingLength` / `serialize` for its
   properties block.
2. **`storage` is `protected`, not `internal`.** KMP's `internal` is per-module; v4/v5
   subclasses in other modules couldn't see it. Exposed public readers:
   `payloadAsReadBufferOrNull()`, `payloadSize()` for persistence/IPC machinery. The public
   API still hides `PayloadStorage` — only `hasRawPayloadBytes: Boolean` leaks the
   variant distinction.
3. **v4 subclass named `PublishMessageV4`, not just using base directly.** Plan implied
   v4 would use base `PublishMessage` concretely (only v5 as subclass), but base would
   then need v4 codec access, breaking module layering. Both versions have subclasses
   for symmetry.
4. **Encode is hand-written** (not via `@ProtocolMessage` codecs) for both v4 and v5.
   The existing codecs' `PayloadReader` callback requires allocation; hand-writing is
   trivial for PUBLISH (length-prefixed topic + optional packetId + [v5: props] + payload).
   Base `ControlPacket.fixedHeader()` writes byte1 + VBI remainingLength automatically.
5. **`payloadSize()` made public on base class.** Needed by `SqlDatabasePersistence` and
   `IDBPersistence` to size buffers for BLOB storage without materializing.
6. **No `PublishMessage.typedPublish(...)` / `rawPublish(...)` companion.** Plan showed
   these but they're sugar — `ControlPacketFactory.publish(...)` already serves the need.
   Can be added later as extension functions in v4/v5 if desired.

## How to verify what's done

```bash
./gradlew :models-base:compileKotlinJvm   # green
./gradlew :models-v4:compileKotlinJvm     # green (warnings only)
./gradlew :models-v4:compileKotlinJs      # green (warnings only)
./gradlew :models-v5:compileKotlinJvm     # green (warnings only)
./gradlew :models-v5:compileKotlinJs      # green (warnings only)
./gradlew :mqtt-client:compileKotlinJvm   # FAILS — next work
```

Tests in v4/v5 also fail to compile (they reference old generic constructors). Work the
test migration in the same pass as whichever module you're editing.

## Branch state

`feature/v2-api-cleanup`. The WIP commit for this refactor is on top of 5 other
v2-cleanup commits. There were also ~60 other pre-existing uncommitted working-tree
modifications to v5 control packet files and tests on this branch when the refactor
started — those are **not** mine and were left uncommitted (work separately).
