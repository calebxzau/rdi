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

`summary` prints one compact JSON object grouped by player, packet type, and
channel. It includes record count, raw payload bytes, first/last Unix
milliseconds, file completeness, and the greatest cumulative
dropped-record snapshot for each run (footer snapshots are not added together).
Group memory is bounded by
`--max-groups`; overflow appears under an `<overflow>` group.

`dump` writes filtered packet records as JSON Lines. `--player`, `--channel`,
and `--type` filter exact values. `--since` and `--until` are elapsed
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
