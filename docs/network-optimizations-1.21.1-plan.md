# Minecraft 1.21.1 networking extensions: Phase 1

Implemented on 2026-10-08 after approval of the reviewed Phase 1 design and its final guard/preparation corrections. This document records the implementation contract and subsequent phases below. Server batching and default-on packet references are now implemented; live combination acceptance and the full performance study remain outstanding.

## Scope and defaults

- Minecraft 1.21.1 NeoForge, S2C streaming activation only.
- `rdi.zstream.enabled=true` by default.
- `rdi.zstream.windowLog=25` by default: a 32MiB history window per connection per side, excluding other native compressor allocations. Invalid values warn and default; out-of-range integers warn and clamp to 20–25.
- Settings are captured once per connection. No active-session hot toggle. Disable streaming and reconnect clients for rollback.
- No L2 Tabs-specific code or delayed-message rules.
- Existing chunk cache, repair, persistence, SyncChunk and C2S compression behavior are preserved.
- The client announces `rdi:zstream`, `rdi:batch`, and `rdi:pktref`; the Phase 1 server announces only `rdi:zstream`. Server batching and references are not activated by this phase.

## Capability contract

`RdiExtensionChannels21` registers presence-only unit payloads as optional PLAY/clientbound channels, version `1`, on both sides. These payloads are never sent. Receiving one is a protocol error.

Only the PLAY map in NeoForge's negotiated `ChannelAttributes.PAYLOAD_SETUP` authorizes an extension. `c:register` and ad-hoc channel declarations are deliberately excluded. The Internal API dependency is isolated in the 1.21 adapter and pinned by a focused test.

Old 1.21 RDI peers with no channels continue using independent Zstd frames. This is not cross-Minecraft-version support and does not add vanilla Deflate compatibility.

## Client preparation and causal ordering

`mConfigurationFinished` injects immediately before the single `Connection.send(Packet)` invocation in `ClientConfigurationPacketListenerImpl.handleConfigurationFinished`, with `require=1`, `allow=1`. It runs after the main-thread check and inbound Play protocol setup, before sending the vanilla Configuration-finish acknowledgement.

`ZstdInboundPreparation.armInbound` performs current decoder lookup, type/identity checks, guard installation and readiness changes in one channel-event-loop task. Its future completes only after that operation. The main-thread caller waits up to five seconds before allowing the original acknowledgement and outbound protocol switch to proceed. Calls made on the event loop execute inline rather than waiting on their own queue.

On failure, timeout or interruption: set a connection-scoped terminal failure immediately, cancel the future, close the connection, log the cause, and cancel the Mixin callback so no acknowledgement is sent. Interruption is preserved. A queued task arriving after cancellation/failure cannot successfully arm the connection.

No negotiated extensions means no arming. Memory connections skip preparation. An absent decoder before any arming represents compression off. A foreign decoder with negotiated capabilities, or a decoder lost after arming, fails the connection. Repeated preparation never resets a live stream or reference table.

The server can enter Play and activate its stream only after receiving the vanilla acknowledgement. There is no additional network handshake or round trip.

## Server activation

`RServerZstdStream` is invoked at player join and runs its eligibility check and activation on the channel event loop. Decisions distinguish disabled settings, memory connections, missing negotiated capability, compression disabled, foreign encoder and successful activation.

The shared encoder flushes prior independent output, writes the existing `STREAM_START` marker, then uses the same streaming history until disconnect. Repeated join callbacks do not restart the stream. Dimension changes and respawn do not reset transport state.

## Guard and failure contract

`ZstdTransportGuard` stores terminal failure and armed handler identities on the channel. Guard queues borrow exact buffer identities without taking ownership:

- Outbound guard: immediately before `compress` in pipeline order, so encoded buffers pass through it before the length prepender. Every encoded buffer, including START/control frames, is registered before write.
- Inbound guard: immediately after `decompress`, before flow control. Every decoded record, including batch children and reference restorations, is registered in FIFO order before delivery.
- Guards consume identity registrations before forwarding, so reentrant delivery cannot use another record's authorization.
- Unexpected identities, a replaced codec, or removal of an armed guard terminates the session. Rejected buffers are released; outgoing promises fail.
- A terminal flag is set before closing. Buffered writes are discarded and failed on exceptional close, rather than flushed through a changed pipeline.
- Controlled compression disable after arming fails before removing handlers. Nonnegative threshold changes preserve live codec contexts.
- Codec removal callbacks provide an additional failure/cleanup layer; they are not the sole data-path protection.
- Normal teardown releases native contexts through codec removal. No client disconnect callback resets a live decoder to Idle.

The protection applies to traffic that traverses the guards while they remain installed. It does not promise to defend against a mod deliberately bypassing the guard contexts or removing/replacing the entire protected path. Extra handlers that copy or transform buffers inside the protected segment are not silently accepted.

Shared guard integration applies to 1.20 as well. Existing feature defaults and wire formats are unchanged; removal of an armed codec or disabling its compression now fails immediately. Legacy tests expecting active codec removal to flush were updated to the approved failure contract.

## Files

- `mc/common/codec/src/main/kotlin/calebxzau/rdi/mc/zstdcodec/ZstdTransportGuard.kt`
- `mc/common/codec/src/main/kotlin/calebxzau/rdi/mc/zstdcodec/ZstdInboundPreparation.kt`
- Existing `ZstdCompressionEncoder.kt`, `ZstdCompressionDecoder.kt`, `ZstdCompressionPipeline.kt`
- `mc/common/codec/src/21/kotlin/calebxzau/rdi/mc/zstdcodec/v21/RdiExtensionChannels21.kt`
- `mc/common/codec/src/21/kotlin/calebxzau/rdi/mc/zstdcodec/v21/ZstreamSettings21.kt`
- Client `ClientNetworkExtensions21.kt`, `mConfigurationFinished.java`, payload registration, Mixin configuration and source-set wiring
- Server `RServerZstdStream.kt`, join callback, payload registration and source-set wiring
- Shared guard/asynchronous preparation tests, 1.21 settings/capability tests, and a server stream-metrics test

The server 1.21 and both Forge 1.20 module-local Windows wrappers were absent in the working tree and were supplied from the existing client 1.21 wrapper (Gradle 9.6.1) to permit the required module-local verification. The isolated 1.20 `zstdTest` configurations now include `netty-handler` for FlowControlHandler tests.

## Metrics

Streaming alone does not defer records. The existing synchronous S2C packet attribution remains in use. A focused test confirms that `STREAM_START` is excluded from per-packet v3 totals and that subsequent segment sizes belong to the correct packet.

Consequently v3 is not complete transport accounting: it excludes START and outer framing. Existing rows are not reinterpreted. Full v4 logical/frame/control accounting and matched A/B results belong to the later measurement phase.

## Validation record

Windows Gradle commands ran through an already-open IntelliJ terminal, from each target module directory. Direct WSL-to-Windows PowerShell startup failed with `UtilBindVsockAnyPort: socket failed 1`; no WSL Gradle was used. The Forge server regression used the required Temurin 17 JAVA_HOME.

Completed before the user's instruction to stop testing; counts were read from JUnit XML:

| Module / task | Tests | Failures / errors / skipped |
| --- | ---: | --- |
| Client 1.21 compileKotlin + compileJava + focused `:test` | 121 | 0 / 0 / 0 |
| Server 1.21 compileKotlin + compileJava + focused `:test` | 129 | 0 / 0 / 0 |
| Client 1.20 Forge `:zstdTest` | 118 | 0 / 0 / 0 |
| Client 1.20 Forge focused `:test` (batch/stream/reference client adapters) | 18 | 0 / 0 / 0 |
| Server 1.20 Forge `:zstdTest` | 118 | 0 / 0 / 0 |

These are module executions, not a count of distinct tests; shared suites run more than once. Initial failures were a source reference error, broad test filtering of unrelated subprojects, missing isolated test dependencies, and old removal/flush expectations. Those were corrected before the successful runs above.

Covered by those runs: all eight codec feature combinations, fragmented/concatenated framed input, strict capability lookup, the required defaults, FlowControl queuing, unexpected buffer identities, replacement-time writes, timeout terminal state, asynchronous readiness on local loopback TCP, a controlled replacement-before-preparation race, and an observed pre-arming START negative control.

The user then requested no further tests. Existing runs had already completed; no new test run was started afterward. Two additional test cases for unread decoder cumulation on removal and reentrant intruder delivery were written but were not included in the completed runs. Final small source hardening (per-connection settings snapshot and explicit installed-context checks during arming) received source review only and was not recompiled/retested after that instruction.

Follow-up review on 2026-10-08 compiled and ran the final source, including the two cases above, then changed `ZstdTransportGuard.fail` to log at debug level when the connection is already inactive or the cause is `ClosedChannelException`, and made `ZstreamSettings21` use the `ZstdStreamFormat` window constants. After these changes, both runs below used the module wrapper (Gradle 9.6.1), with counts taken from JUnit XML:

| Module / task | Tests | Failures / errors / skipped |
| --- | ---: | --- |
| Server 1.21 compileKotlin + focused `test` (codec, v21, server network) | 132 | 0 / 0 / 0 |
| Server 1.20 Forge `zstdTest` | 120 | 0 / 0 / 0 |

Before these changes, the client 1.21 compile plus codec `:test` run passed 123 / 0 / 0. It was not rerun afterwards: the changed shared sources are covered by the server runs above.

Not performed: live Mixin application, real-modpack login/new-old peer matrix, dimension travel/respawn/reconnect, chunk-cache repair and SyncChunk acceptance, ten-player native-memory observation, or A/B bandwidth/latency measurements. Automated success does not establish those runtime results.

## Phase 2: selective attribute batching (2026-10-08)

Server only; the Phase 1 client already announces and prepares `rdi:batch`. Default `rdi.batch.enabled=false`, sampled once per connection.

- The server now registers `rdi:batch`. `RServerBatching` activates at player join on the event loop using the shared `ExtensionDecision21` order (disabled, memory connection, not negotiated, compression off, foreign encoder). It first enables per-packet classification, then calls `setOutboundBatching(..., delayUnassociated = false)` in one event-loop task.
- `PacketRecordPipeline` (installed at connection setup, independent of metrics) opens a scope around each outbound write before the bundle unpacker and encoder. The `PacketEncoder` RETURN hook records `output buffer -> packet` by identity, and the handler after the encoder takes it back and wraps it in `ZstdSendingRecord`. A failed encode never reaches that handler and its scope is dropped.
- `PacketBatchPolicy21`: only the wire type `GamePacketTypes.CLIENTBOUND_UPDATE_ATTRIBUTES` is `OneTick`; every other packet, custom payloads and unassociated buffers included, is `Immediate`. Attributes inside a bundle use the same rule; the closing delimiter flushes them. Matching by wire type keeps the attribute class and its registry codecs uninitialised.
- Shared codec: `ZstdCompressionEncoder` gained a per-connection policy for unassociated buffers. The default stays `OneTick`, so 1.20 is unchanged; 1.21 sets `Immediate` before batching starts.
- One flush per batching connection at `ServerTickEvent.Post`, plus the existing size, barrier and 50ms first-record limits. No FourTicks or L2 Tabs rules, and no per-tick setting reload.
- Metrics: on batching connections, the v3 per-packet S2C measurement is skipped, because frames no longer belong to the packet being written. Block-level v4 accounting remains Phase 4. The previously disabled codec-failure test is restored: the oversized-packet case moved to its own test, because the encoder correctly closes the connection on that error.

Validation, counts from JUnit XML:

| Module / task | Tests | Failures / errors / skipped |
| --- | ---: | --- |
| Server 1.21 compile + codec, v21 and server network tests | 145 | 0 / 0 / 0 |
| Client 1.21 compile + codec `:test` | 126 | 0 / 0 / 0 |
| Server 1.20 Forge compileKotlin + `zstdTest` | 121 | 0 / 0 / 0 |
| Client 1.20 Forge compileKotlin + `zstdTest` | 121 | 0 / 0 / 0 |

Not performed: live server run with `-Drdi.batch.enabled=true`, combat/mob-spawn attribute traffic, dimension change, respawn, reconnect, and old/new peer matrix.

## Phase 4 (part 1): v4 transport accounting (2026-10-08)

- The 1.21 server now starts `rdi/packet-traffic_v4.db` like 1.20 (`PacketMetrics.startBatchMetrics`). An existing `packet-traffic_v3.db` is left untouched and no longer written.
- S2C logical bytes are recorded per packet type through the encoder observer; every associated packet now carries its identity, batched or not. Connections without the RDI encoder record one `Uncompressed` frame per packet. C2S keeps per-packet compressed frame bytes in `packet_batch_inbound_totals`.
- Every emitted inner frame is counted once in `packet_batch_frame_totals`, with its outer prefix. The shared encoder now also reports `STREAM_START` and packet-reference `START` as record-free `Control` frames. This applies to 1.20 as well, which previously omitted them.
- An end-to-end test checks that the summed frame bytes equal the bytes actually written, including the control frame.

Still to do: matched A/B runs (streaming off, streaming only, plus batching) on a real multi-player session.

## Later phases

Phase 3: server packet-reference activation implemented on 2026-10-09, default-on and exactly once per connection. Live combination acceptance remains outstanding.

Phase 4: matched A/B workload measurements on top of the v4 accounting above.


## Packet-content capture implementation and validation (2026-10-08)

Implemented the approved capture design and subsequent contract corrections;
`/tmp/rdi-packet-capture-1.21.1-plan.md` was not modified. The 1.21 adapter starts
recording custom payloads and attributes at its Configuration task event callback,
keeps the same capture connection through Play/reconfiguration, and executes
attachment immediately when already on the event loop. The live encoder supplies
the phase; unknown phases are logged/skipped. Capture is default-on with the
`rdi.capture.enabled=false` opt-out. The shared writer emits RDPC v2; 1.20 records
Play. The Python reader accepts v1/v2 and provides phase filtering and `analyze`.

Analysis preserves raw records and separate bounded split reassembly. It validates
NeoForge's byte-array length prefix, isolates runs/connections/phases, abandons
incomplete chains, and checks the original packet ID before parsing a channel.
Exact percentiles and record spooling use a disposable capped SQLite database.
Compression/reference results are explicitly captured-subset simulations, with
variable reference widths and separately reported control/finish overheads.
See `mc/common/codec/tools/packbatch/README.md` for metric and resource contracts.

Validation ran through IntelliJ MCP using the Windows module-local wrappers:

| Validation | Result |
| --- | --- |
| 1.21 server `compileKotlin compileJava` and focused `:test` (`calebxzau.rdi.mc.zstdcodec.*`, `calebxzhou.rdi.mc.server.network.*`) | 155 tests, zero failures/errors/skips |
| 1.20 server Temurin17 `compileKotlin`, `:zstdTest` | 122 tests, zero failures/errors/skips |
| 1.20 server `:test --tests calebxzau.rdi.mc.v20.*` | 57 tests, zero failures/errors/skips |
| 1.20 client Temurin17 `:zstdTest` | 122 tests, zero failures/errors/skips |
| Python unittest with the exported Kotlin v2 fixture | 32 tests, zero failures/errors/skips |

Counts were read from the corresponding `build/test-results/{test,zstdTest}/TEST-*.xml`
and Python unittest output. The test task forwards `rdi.packbatch.fixtureDir` to
the test JVM; the resulting fixture in `server/mc/1.21.1-neoforge/build/capture-fixture`
was read and compared by Python with `RDI_PACKBATCH_FIXTURE_DIR` set. Local Python
validation used a PyPI zstandard0.25.0 wheel extracted under `/tmp`, because the
WSL Python had neither zstandard nor ensurepip; its SHA-256 matched PyPI metadata.
No additional project dependency was introduced.

Focused cases include inline attach before an immediate configuration send,
reconfiguration identity/sequence, current real-encoder phase lookup, unknown
phase, encoder failure, bundle children, connection cleanup, disabled capture
creating no files, clean empty-run close, and outgoing byte equality with capture
on/off for compression-off, batching and streaming modes. Capture pipeline tests
use stand-in packets to avoid unbootstrapped Minecraft registries; they do not
prove real Mod codecs or Mixin application. Shared recorder tests cover source
indices/refcounts, bounds, rotation, writer failure, reconnect IDs and shutdown.
Python tests cover v1/v2, unknown phases, real Kotlin output, split framing,
non-custom originals, missing/corrupt inputs, cross-run isolation, connection-wide
LRU and slot127/128 widths, duplicate/group/database limits and exact statistics.

Not performed: a live server/client join, actual Configuration registry/config
payload capture, dimension-change/reconnect gameplay, or runtime overhead/disk
volume measurement. Existing server worlds were not started for this validation.
The build/test results are not a real-game acceptance claim.


## Phase 3: default-on packet references (2026-10-09)

The 1.21 server now announces `rdi:pktref` alongside `rdi:zstream` and `rdi:batch`.
References start after player join only for a negotiated PLAY capability on a
remote connection with an RDI compression encoder. Ad-hoc channel announcements,
unnegotiated peers, memory connections, compression-off and foreign encoders do
not enable references. Client preparation and the existing START format are reused.

`rdi.pktref.enabled` defaults to `true`; `-Drdi.pktref.enabled=false` disables
activation for new connections. Settings are sampled once per connection, so
changing a property does not toggle an active session. Table defaults are256 slots
and1024 bytes per entry; `rdi.pktref.slots` clamps to1–1024 and
`rdi.pktref.maxEntryBytes` to8–2048. Invalid integer settings warn and use defaults.

Activation runs on the connection event loop after the existing stream and batch
activation tasks. A per-connection flag is set before requesting START, preventing
both repeated join callbacks and downstream write/flush reentrancy from resetting
the table. START/activation failure uses the existing transport abort path rather
than continuing with uncertain cache state. Shared codec framing and 1.20 activation
were not changed.

Validation: Windows IntelliJ MCP, module-local `compileKotlin compileJava` plus
focused tests. Server34 tests (new settings3, capability2, adapter4, shared packet
reference codec25), client5 tests (settings3 and capability2); JUnit XML reports
zero failures/errors/skips. Cases include default/explicit-disable settings,
negotiation versus ad-hoc presence, eligibility, per-connection setting snapshots,
reentrant/duplicate START and byte-exact round trips for all four batch/stream
combinations. Logs: each module's `build/pktref-validation-20261009.log`.
Real server/client connections and measured bandwidth/performance were not tested.
