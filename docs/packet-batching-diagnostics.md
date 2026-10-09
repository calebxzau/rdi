# Packet batching and packet-content diagnostics

Forge1.20.1 automatically records only `ClientboundCustomPayloadPacket` and
`ClientboundUpdateAttributesPacket` contents for every joined player. Other packet
types are skipped before copying or allocating a capture sequence and do not
increase the dropped-record count. The custom-payload selection includes all
channels of that packet class. Capture works with legacy clients and with network
compression or negotiated batching disabled. It begins when the server handles
the player-joined event; earlier login/bootstrap packets are outside this capture.
There are no `rdi.batch.sample*` settings or capture time limits.

## Files

Files are written under the server working directory:

```text
rdi/packbatch/<run-uuid>-000001.rdibatch.zst
rdi/packbatch/<run-uuid>-000002.rdibatch.zst
rdi/packbatch/<run-uuid>.json
```

Each part is at most **128,000,000 bytes**, including its Zstd frames and footer.
The server uses one writer for all players, a global 16MiB pending-record budget,
and a 32768-record queue. Compression uses independent Zstd frames at level3
with checksums. The writer flushes small blocks periodically instead of retaining
an entire run in memory. The JSON sidecar records versions once per run.

The bytes are captured before transport compression and include the Minecraft
packet ID. They describe successfully encoded packets, not guaranteed delivery.
File size and raw payload size are not wire-traffic measurements; use
`packet-traffic_v4.db` for transport aggregates.

Queue overload drops capture copies, increments the drop counter, and resumes
when capacity is available. Connection sequence gaps and cumulative drop counts
make lost samples visible. Disk/compression failure stops capture and logs the
exception while normal packet sending continues. Normal shutdown drains accepted
records within a bounded wait. In-progress or interrupted files have a `.partial`
suffix; complete Zstd frames in them can be salvaged by the Python reader.

Every run has a UUIDv7 ID, and reconnects get new connection numbers. Parts are
independently inspectable, and file rotation does not flush network batches.
There is no automatic deletion or total-directory quota; 128MB limits each file,
not the accumulated directory.

## Python analysis

See [the Python tool instructions](../mc/common/codec/tools/packbatch/README.md) for install,
summary, filtered JSONL output and validation. New tools read only the RDPC
format described in [packbatch-format.md](packbatch-format.md).

## Network batching settings

These settings control network batching separately from automatic content capture:

| Property | Default | Meaning |
| --- | --- | --- |
| `rdi.batch.enabled` | `true` | Batch packets for compatible clients; false uses the legacy per-packet path. |
| `rdi.batch.longWindow` | `false` | Permit verified messages to wait four ticks instead of one. |
| `rdi.pktref.enabled` | `false` | Replace repeated server-to-client packets with references; see [packet-ref-dedup.md](packet-ref-dedup.md). |
| `rdi.pktref.slots` | `256` | Packet reference table slots, clamped to 1-1024. |
| `rdi.pktref.maxEntryBytes` | `1024` | Largest packet the reference table records, clamped to 8-2048. |

In `packet_batch_frame_totals`, `frame_kind = 'Ref'` rows count packet references.
For them, `sum_replaced_frame_bytes` holds the legacy frames they replaced, so
`sum_replaced_frame_bytes - sum_frame_bytes - sum_outer_prefix_bytes` is the frame
bytes saved. The column is 0 for every other frame kind.

The existing synthetic codec replay tests remain useful for packet-byte and
batching-policy correctness. The new content log omits replay-only events and is
an analysis input, not a same-stream compression benchmark.


## 1.21.1 NeoForge packet-content capture

Capture is enabled by default. Disable it with `-Drdi.capture.enabled=false`.
It writes custom payload and attribute-update packets for all remote players to
`rdi/packbatch`, independently of batch/stream negotiation and metrics. Recording
starts at the `RegisterConfigurationTasksEvent` callback, before subsequent task
sends, and continues into Play. Initial negotiation and brand packets sent before
that callback are excluded. The loader routes this `IModBusEvent` to the mod bus
from the existing subscriber annotation; no `bus` argument is used.

The shared writer now emits RDPC v2 with a phase byte; 1.20 emits Play and the
Python reader remains compatible with v1. Parts are at most 128,000,000 bytes.
There is no run-total or directory quota and no automatic retention. The writer
uses the existing global 16MiB/32768-record admission limits and reports drops.
An open part uses `.partial`; clean close/rotation writes a footer and renames it.
The sidecar is created at startup; the first part may appear when the first data
frame is flushed (or on shutdown of an empty run), not necessarily at startup.

```text
python packbatch.py summary rdi/packbatch
python packbatch.py dump rdi/packbatch --phase configuration --limit 20
python packbatch.py analyze rdi/packbatch --top 20 --json
```

The analysis models only the captured subset. Neither compression simulation is
actual transport cost or a guaranteed bound; other packets, negotiated thresholds,
flush boundaries and batching may change results. Raw and reassembled views must
not be summed. See the [reader guide](../mc/common/codec/tools/packbatch/README.md)
for limits, incomplete-data handling and simulation assumptions.


## 1.21.1 pktref default

As of2026-10-09, the NeoForge1.21.1 server enables packet references by default for
clients that negotiated `rdi:pktref`. Use `-Drdi.pktref.enabled=false` and reconnect
(or restart) to disable it. Default table settings are256 slots and1024 bytes per
entry, configured by `rdi.pktref.slots` and `rdi.pktref.maxEntryBytes`. Unsupported
peers keep plain packets; the shared compression/batching protocol is unchanged.
START is sent once per connection. This setting does not change packet capture.
