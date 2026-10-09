# Packbatch reader

This Python CLI reads the new `.rdibatch.zst` packet capture files. It does not
read the older RDST capture format. Install its only dependency with
`python -m pip install -r requirements.txt`.

The reader streams concatenated independent Zstandard frames and bounds each
compressed frame to 10 MiB and each decompressed frame to 9 MiB. Capture files
must be at most 128,000,000 bytes. Complete frames before a truncated or corrupt
tail are still analyzed, with an incomplete-file diagnostic. `.partial` files
are accepted.

Commands:

```text
python packbatch.py summary rdi/packbatch
python packbatch.py dump rdi/packbatch --player 019... --channel example:channel --limit 50
```

`summary` prints one compact JSON object grouped by player, protocol phase, packet type, and
channel. It includes record count, raw payload bytes, first/last Unix
milliseconds, file completeness, and the greatest cumulative
dropped-record snapshot for each run (footer snapshots are not added together).
Group memory is bounded by
`--max-groups`; overflow appears under an `<overflow>` group.

`dump` writes filtered packet records as JSON Lines. `--player`, `--channel`,
`--phase play|configuration`, and `--type` filter exact values. `--since` and `--until` are elapsed
nanoseconds since the run start (inclusive). `--limit` defaults to 100. Payload
output is disabled by default; choose `--payload hex` or `--payload base64` to
include it. It scans and validates all supplied files even after reaching the
output limit, then writes a status object to stderr with matched/emitted counts
and `limit_reached`. Diagnostics are structured JSON on stderr. Any corrupt/truncated
frame, missing footer, missing/duplicate supplied part, or connection sequence
gap produces a nonzero exit status. A first observed sequence greater than 1 is
allowed because the supplied files may be a selection from a larger capture.
Sequence tracking retains at most 65,536 connections and reports when that cap
prevents further checks; gap diagnostics aggregate counts and retain at most five
examples.

Pass file paths or directories. Directory discovery is non-recursive and
includes `.rdibatch.zst` and `.rdibatch.zst.partial`. Files are ordered by the
run ID and part number in their validated headers, not by filename. Records
preserve meaningful order within a connection. Interleaving between different
connections is writer arrival order and is not presented as global network
chronology.

Run the independent Python fixtures with `python -m unittest -v` from this
directory.


## RDPC v2 and optimization analysis

Both v1 (implicit Play) and v2 (explicit Play/Configuration) are accepted. Unknown
phase values invalidate their data frame and produce a diagnostic/nonzero status.
The binary format is described in [packbatch-format.md](../../../../../docs/packbatch-format.md).

```text
python packbatch.py analyze rdi/packbatch --top 20
python packbatch.py analyze rdi/packbatch --group phase,type,channel --json
```

`analyze` emits a table, or one JSON object per group with `--json`. Stderr always
contains an `analysis_model` object and structured diagnostics. `complete=false`
and a nonzero exit status mean input/model coverage is incomplete. Resource errors
stop analysis without presenting a partial table as complete. Top-N limits output,
not processing. Default grouping is phase/type/channel; player may also be used.

Raw records retain every captured byte, including the packet ID VarInt. Separate
`view=reassembled` rows describe successfully joined `neoforge:split` payloads;
these rows have only count, size, share and maximum connection-byte totals. Shares
use the total for that same view. Never add raw and reassembled totals. Ordinary
payloads are already visible in raw groups and are not duplicated into joined rows.
Use `--no-split-join` to omit the reassembled view.

Split framing is packet ID, channel string, byte-array length VarInt, then state
byte (1 first / 0 middle / 2 last) and slice. The reassembled packet ID must match
the enclosing custom-payload packet ID before decoding its channel. Other original
packet types remain `<unclassified>`. Parsing checks lengths, states and bounds.
Missing parts, corrupt tails, sequence gaps, phase changes, replacement first
fragments and EOF abandon incomplete chains and report diagnostics. Run and
connection IDs isolate all order-sensitive state. Phase transitions reset model
state even if grouping omits phase. The input must be a stable capture snapshot.

| Fields | Meaning |
| --- | --- |
| `count`, `bytes`, `bytes_share` | Captured-record count and encoder bytes, share within the view |
| `size_p50/p90/p99/max` | Exact nearest-rank percentiles using a disk-backed index |
| `per_connection_bytes_max` | Maximum byte total for one `(run, connection)` |
| `dup_any_ratio` | Within-group, same connection/phase segment BLAKE2b-128 digest repeat fraction; digest matching is probabilistic, not byte-equality proof |
| `ref_eligible_dup_ratio` | Byte-equal hits / eligible records in one connection-level LRU, across groups, for lengths 8 through the configured maximum |
| `ref_saved_bytes_est` | Gross simulated savings versus independent legacy envelopes, including outer VarInt lengths; per-slot reference width is variable |
| `zstd_independent_bytes` | Sum of standalone level-3 Zstd outputs with checksum/content-size disabled; excludes network envelopes |
| `zstd_stream_bytes` | Output growth from a level-3/window-log-25 stream with block flush after each captured record; excludes envelopes; finish bytes reported separately |
| `delta_changed_bytes_mean`, `delta_changed_ratio` | New length minus non-overlapping equal prefix/suffix against previous same-group record, averaged per compared pair and divided by summed target lengths; not an encoded delta size |
| `burst_max_per_50ms`, `burst_max_per_1s` | Independent maximum count and bytes in half-open sliding windows on capture timestamps, per connection/group |

Reference simulation defaults: `--ref-window 256`, `--ref-max-entry 1024`,
`--threshold 256`. Slots follow connection-wide LRU, never one cache per channel.
The baseline uses uncompressed envelopes below the given threshold and the stated
independent Zstd model above it. Hits are substituted only when smaller. START
bytes are reported separately as `reference_start_bytes`; subtract that aggregate
from gross group savings for the simulation's net result. `stream_finish_bytes`
is likewise a separate aggregate, not assigned to an arbitrary channel. Models
reset on phase transitions and input discontinuities. These are explicitly
captured-subset scenarios: actual server thresholds, excluded traffic, stream
activation, batching and timing are not reconstructed. Neither result is actual
wire savings or a mathematical lower/upper bound.

Resource defaults and failure behavior:

- `--max-groups 10000`: shared cap across raw/reassembled groups; exceeding it stops analysis.
- `--max-spool-bytes 8589934592`: SQLite database cap, including records, indexes,
  digest tables and exact size samples; a full database stops analysis. `--work-dir`
  selects the temporary database parent. The disposable database disables journaling,
  uses file-backed temporary storage and an 8MiB page cache. Temporary SQLite sort
  files and filesystem overhead are additional to the database cap.
- Connections are processed sequentially, with one window-log-25 compressor and
  one reference table at a time. Completed connections release that state.
- `--max-digests 1000000`: per-segment disk-backed digest cap; further duplicate
  samples are omitted with a diagnostic and affected `dup_any_ratio` becomes null.
- `--max-split-bytes 67108864`: one active connection's reassembly limit; oversized
  chains are discarded and reported. Reassembled copies are transient and bounded.
- Previous-payload storage for delta comparison is limited to 16MiB with LRU eviction;
  omitted pairs are reported. Burst queues have a global 65536-entry limit for the
  active connection; exceeding it stops analysis. These limits, plus group metadata,
  bounded input frames and native compressor state, are not a process-RSS guarantee.
- Analysis diagnostics aggregate repeated occurrences and cap distinct messages.

Kotlin-to-Python verification uses the real writer fixture, not a Python facsimile.
The 1.21 Gradle test task explicitly forwards `rdi.packbatch.fixtureDir` to its JVM.
Set `RDI_PACKBATCH_FIXTURE_DIR` to that directory when running Python tests. Without
that variable, only this cross-language test is skipped and must not be counted as
verified interoperability.
