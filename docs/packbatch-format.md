# Packet capture format

The Forge 1.20.1 server records successfully encoded `ClientboundCustomPayloadPacket`
and `ClientboundUpdateAttributesPacket` packets for all joined players, independently
of batch negotiation and compression settings. Other packet types are filtered out
before copying, queue admission or connection-sequence allocation. Filtered packets
are not counted as dropped capture records. Custom payloads are selected by packet
class, without a channel namespace whitelist.
These are packet-encoder bytes, not proof of delivery or measured wire bytes.

Files live in `rdi/packbatch/<run-uuid>-<part:06>.rdibatch.zst`, with a hard
128,000,000-byte limit including all frames. Each file is a concatenation of
complete, independent Zstd frames (level 3, checksum enabled). No packet crosses
a frame or file boundary. The writer targets 1 MiB of uncompressed records per
data frame; a larger packet occupies its own frame. Partial files end in
`.rdibatch.zst.partial`. Readers may salvage complete frames from partial files.

All integers in decompressed frame bodies are big-endian. UUIDs are their 16
network-order bytes. Strings are a u16 UTF-8 byte length followed by that many
bytes, at most 4096 bytes. Unknown packet types use `:unknown-encoded`; absent
custom channels use an empty string. The current format version is 1. This is
a new format, unrelated to the earlier RDST replay captures.

Each Zstd frame decompresses to exactly one of these structures:

| Kind byte | Body |
| --- | --- |
| 1 (header) | ASCII `RDPC` (4 bytes), u16 version, UUID run ID, i64 run start Unix milliseconds, u32 part number (starts at 1) |
| 2 (data) | u32 record count, followed by that many packet records |
| 3 (footer) | u64 records in this file, u64 cumulative dropped records for this run, u8 final (0=rotation, 1=normal shutdown), i64 elapsed nanoseconds at close |

Each packet record contains, in order:

1. i64 nanoseconds elapsed since run start (monotonic).
2. UUID player ID.
3. u64 connection ID, increasing from 1 within the run; reconnects get a new ID.
4. u64 connection sequence, increasing from 1 for every attempted record,
   including attempts rejected by queue/size limits.
5. Packet type string.
6. Custom channel string (`namespace:path` or empty).
7. u32 payload length, at most 8 MiB.
8. Exact payload bytes (includes the encoded Minecraft packet ID).

The header must be first, and the footer must be last. Data frames contain at
least one record. Maximum uncompressed frame size is 9 MiB; maximum compressed
frame size accepted by readers is 10 MiB. The writer bounds data record counts
to 32768 per frame. Oversized payloads/labels are dropped, never truncated.

File rotation is a storage operation only and does not flush network batches.
Per-connection order is meaningful; interleaving between different connections
is writer arrival order, not a global network chronology. Dropped counts are
cumulative snapshots, so readers must not sum footers from the same run.
Each part is independently inspectable. Missing parts, sequence gaps, partial
frames and absent footers must be reported, not silently treated as complete.

A `<run-uuid>.json` sidecar records Minecraft/loader/Mod versions once per server
run. It is helpful context, not required to decode any part. Run IDs are UUIDv7.
The capture uses a global 16 MiB pending-data budget and a 32768-record queue;
compression buffers are bounded separately. Overflow drops capture copies and
increments the counter, without blocking network writes. There is no automatic
retention deletion or total-directory quota.
