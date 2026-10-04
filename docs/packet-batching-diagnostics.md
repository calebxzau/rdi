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
