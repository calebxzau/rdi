"""Disk-spooled, bounded analysis of a captured subset, never a wire-bandwidth claim."""
from __future__ import annotations

import hashlib
import json
import math
import sqlite3
import sys
import tempfile
from contextlib import closing
from collections import OrderedDict, deque
from pathlib import Path

import zstandard


def add_parser(subparsers):
    parser = subparsers.add_parser("analyze", help="analyze captured bytes and explicit compression models")
    parser.add_argument("paths", nargs="+")
    parser.add_argument("--group", default="phase,type,channel")
    parser.add_argument("--top", type=int, default=20)
    parser.add_argument("--ref-window", type=int, default=256)
    parser.add_argument("--ref-max-entry", type=int, default=1024)
    parser.add_argument("--threshold", type=int, default=256, help="simulation threshold, not detected server setting")
    parser.add_argument("--no-split-join", action="store_true")
    parser.add_argument("--json", action="store_true")
    parser.add_argument("--max-groups", type=int, default=10000)
    parser.add_argument("--max-digests", type=int, default=1000000)
    parser.add_argument("--max-split-bytes", type=int, default=64 * 1024 * 1024)
    parser.add_argument("--max-spool-bytes", type=int, default=8 * 1024 * 1024 * 1024)
    parser.add_argument("--work-dir", help="parent of the temporary SQLite spool (default: system temp)")


def varint_size(value):
    return max(1, (value.bit_length() + 6) // 7)


def framed(size):
    return varint_size(size) + size


def read_varint(data, offset=0):
    value = 0
    for index in range(5):
        if offset >= len(data):
            raise ValueError("truncated VarInt")
        byte = data[offset]
        offset += 1
        if index == 4 and byte > 15:
            raise ValueError("oversized VarInt")
        value |= (byte & 127) << (7 * index)
        if byte < 128:
            return value, offset
    raise ValueError("oversized VarInt")


def custom_header(data):
    packet_id, offset = read_varint(data)
    length, offset = read_varint(data, offset)
    if length > 32767 or offset + length > len(data):
        raise ValueError("invalid channel length")
    channel = bytes(data[offset:offset + length]).decode("utf-8", errors="strict")
    if ":" not in channel or not channel or any(c not in "abcdefghijklmnopqrstuvwxyz0123456789_-.:/" for c in channel):
        raise ValueError("invalid channel")
    return packet_id, channel, offset + length


class SplitJoin:
    """One connection at a time. Raw records remain untouched and independently accounted."""
    def __init__(self, limit, issue):
        self.limit, self.issue = limit, issue
        self.pending = None

    def reset(self, reason):
        if self.pending is not None:
            self.issue("incomplete_split", reason)
            self.pending = None

    def accept(self, record):
        if record["channel"] != "neoforge:split":
            return
        try:
            data = record["_payload"]
            packet_id, channel, offset = custom_header(data)
            if channel != "neoforge:split":
                raise ValueError("split metadata disagrees with encoded channel")
            length, offset = read_varint(data, offset)
            if length < 1 or length != len(data) - offset:
                raise ValueError("split byte-array length mismatch")
            state, piece = data[offset], memoryview(data)[offset + 1:]
            if state not in (0, 1, 2):
                raise ValueError("unknown split state")
            if state == 1:
                self.reset("new first fragment before last fragment")
                self.pending = (packet_id, bytearray(), record["elapsed_ns"])
            if self.pending is None:
                raise ValueError("split fragment without first fragment")
            original_id, joined, started = self.pending
            if packet_id != original_id:
                raise ValueError("split packet ID changed")
            if len(joined) + len(piece) > self.limit:
                raise ValueError("split reassembly byte budget exceeded")
            joined.extend(piece)
            if state != 2:
                return
            self.pending = None
            joined_id, _ = read_varint(joined)
            joined_type, joined_channel = "<unclassified>", ""
            if joined_id == original_id:
                _, joined_channel, _ = custom_header(joined)
                joined_type = record["type"]
            return {**record, "type": joined_type, "channel": joined_channel,
                    "payload_length": len(joined), "_payload": bytes(joined),
                    "first_elapsed_ns": started}
        except (ValueError, UnicodeError) as exc:
            self.reset(str(exc))
            self.issue("invalid_split", str(exc))


class ReferenceModel:
    """One connection/phase segment LRU; grouping never creates separate caches."""
    def __init__(self, slots, maximum):
        self.slots, self.maximum = slots, maximum
        self.cache = OrderedDict()

    def observe(self, payload, baseline):
        if not 8 <= len(payload) <= self.maximum:
            return False, 0
        if payload in self.cache:
            slot = self.cache[payload]
            self.cache.move_to_end(payload)
            cost = framed(1 + varint_size(slot) + 4)
            return True, max(0, baseline - cost)
        slot = len(self.cache)
        if slot == self.slots:
            _, slot = self.cache.popitem(last=False)
        self.cache[payload] = slot
        return False, 0


class Analyzer:
    def __init__(self, database, args):
        self.db, self.args = database, args
        self.fields = args.group.split(",")
        if len(set(self.fields)) != len(self.fields) or not set(self.fields) <= {"phase", "type", "channel", "player"}:
            raise ValueError("--group accepts distinct phase,type,channel,player fields")
        if not (1 <= args.max_groups <= 100000 and 1 <= args.ref_window <= 1024 and
                8 <= args.ref_max_entry <= 2048 and args.threshold >= 0 and args.top >= 1 and
                args.max_digests >= 1 and 1 <= args.max_split_bytes <= 256 * 1024 * 1024 and
                args.max_spool_bytes >= 1024 * 1024):
            raise ValueError("invalid analysis limit")
        self.groups = {}
        self.diagnostics = OrderedDict()
        self.start_cost = 0
        self.stream_tail = 0
        self.db.executescript('''
            PRAGMA journal_mode=OFF;
            PRAGMA synchronous=OFF;
            PRAGMA temp_store=FILE;
            PRAGMA cache_size=-8192;
            CREATE TABLE records(run TEXT, connection TEXT, ordinal INTEGER PRIMARY KEY,
                sequence TEXT, elapsed INTEGER, phase TEXT, type TEXT, channel TEXT,
                player TEXT, payload BLOB, boundary INTEGER);
            CREATE INDEX connection_order ON records(run, connection, ordinal);
            CREATE TABLE sizes(g INTEGER, size INTEGER);
            CREATE INDEX group_sizes ON sizes(g,size);
            CREATE TABLE digests(g INTEGER, digest BLOB, PRIMARY KEY(g,digest)) WITHOUT ROWID;
        ''')
        # Caps the database, including indexes, temporary analysis tables and spool payloads.
        self.db.execute(f"PRAGMA max_page_count={args.max_spool_bytes // 4096}")

    def issue(self, code, message):
        key = (code, message)
        if key not in self.diagnostics and len(self.diagnostics) >= 256:
            key = ("diagnostic_overflow", "additional distinct diagnostics omitted")
        self.diagnostics[key] = self.diagnostics.get(key, 0) + 1

    def ingest(self, files):
        from packbatch import read_file
        ordinal, boundary = 0, 0
        seen_parts = set()
        prior_run, prior_part = None, 0
        for path, header in files:
            if header:
                key = (header.run_id, header.part)
                if key in seen_parts:
                    self.issue("duplicate_part", f"duplicate part excluded: {key}")
                    boundary += 1
                    continue
                seen_parts.add(key)
                if header.run_id != prior_run or header.part != prior_part + 1:
                    boundary += 1
                prior_run, prior_part = key
            else:
                boundary += 1
            diagnostics, infos = [], []
            for record in read_file(path, diagnostics, infos):
                ordinal += 1
                self.db.execute("INSERT INTO records VALUES(?,?,?,?,?,?,?,?,?,?,?)", (
                    record["run_id"], str(record["connection_id"]), ordinal, str(record["sequence"]),
                    record["elapsed_ns"], record["phase"], record["type"], record["channel"],
                    record["player"], record["_payload"], boundary))
            if diagnostics:
                boundary += 1
                for diagnostic in diagnostics:
                    self.issue(diagnostic.code, diagnostic.message)
            if infos and infos[0].dropped:
                self.issue("capture_drops", f"run {infos[0].header.run_id} reports dropped capture records")
        self.db.commit()

    def group(self, record, view="raw"):
        key = (view,) + tuple(record[field] for field in self.fields)
        if key not in self.groups:
            if len(self.groups) >= self.args.max_groups:
                raise ValueError("group budget exceeded; analysis stopped")
            self.groups[key] = dict(view=view, **dict(zip(self.fields, key[1:])),
                _id=len(self.groups), count=0, bytes=0, per_connection_bytes_max=0,
                dup_hits=0, dup_samples=0, ref_hits=0, ref_eligible=0, ref_saved_bytes_est=0,
                zstd_independent_bytes=0, zstd_stream_bytes=0, delta_changed_bytes=0,
                delta_pairs=0, delta_target_bytes=0,
                burst_max_per_50ms={"count": 0, "bytes": 0}, burst_max_per_1s={"count": 0, "bytes": 0})
        return self.groups[key]

    def count(self, record, view="raw"):
        group = self.group(record, view)
        group["count"] += 1
        group["bytes"] += record["payload_length"]
        self.db.execute("INSERT INTO sizes VALUES (?,?)", (group["_id"], record["payload_length"]))
        return group

    def process(self):
        compressor = zstandard.ZstdCompressor(level=3, write_checksum=False, write_content_size=False)
        connections = self.db.execute("SELECT DISTINCT run,connection FROM records ORDER BY run,connection")
        for run, connection in connections:
            totals, previous, windows = {}, OrderedDict(), {}
            previous_bytes, window_records, digest_count = 0, 0, 0
            self.db.execute("DELETE FROM digests")
            splitter = SplitJoin(self.args.max_split_bytes, self.issue)
            stream, segment, last_sequence, last_time = None, None, 0, -1
            refs = None
            cursor = self.db.execute("SELECT sequence,elapsed,phase,type,channel,player,payload,boundary "
                                     "FROM records WHERE run=? AND connection=? ORDER BY ordinal", (run, connection))
            for sequence, elapsed, phase, kind, channel, player, payload, boundary in cursor:
                sequence = int(sequence)
                record = dict(run_id=run, connection_id=connection, sequence=sequence, elapsed_ns=elapsed,
                              phase=phase, type=kind, channel=channel, player=player,
                              _payload=payload, payload_length=len(payload))
                changed = segment != (phase, boundary) or sequence != last_sequence + 1 or elapsed < last_time
                if changed:
                    if sequence != last_sequence + 1:
                        self.issue("sequence_gap", "connection sequence discontinuity; model state reset")
                    if elapsed < last_time:
                        self.issue("timestamp_order", "connection timestamp moved backwards; model state reset")
                    splitter.reset("phase, part, sequence or timestamp discontinuity")
                    if stream is not None:
                        self.stream_tail += len(stream.flush(zstandard.COMPRESSOBJ_FLUSH_FINISH))
                    parameters = zstandard.ZstdCompressionParameters.from_level(3, window_log=25)
                    stream = zstandard.ZstdCompressor(compression_params=parameters).compressobj()
                    refs = ReferenceModel(self.args.ref_window, self.args.ref_max_entry)
                    # Protocol START: VarInt(-3), opcode, version, slots, maximum, outer length.
                    self.start_cost += framed(5 + 1 + 1 + varint_size(self.args.ref_window) + varint_size(self.args.ref_max_entry))
                    self.db.execute("DELETE FROM digests")
                    digest_count = 0
                    previous.clear()
                    windows.clear()
                    previous_bytes, window_records = 0, 0
                segment, last_sequence, last_time = (phase, boundary), sequence, elapsed
                group = self.count(record)
                gid, size = group["_id"], len(payload)
                totals[gid] = totals.get(gid, 0) + size
                independent = len(compressor.compress(payload))
                group["zstd_independent_bytes"] += independent
                group["zstd_stream_bytes"] += len(stream.compress(payload)) + len(stream.flush(zstandard.COMPRESSOBJ_FLUSH_BLOCK))
                baseline = framed(1 + size) if size < self.args.threshold else framed(varint_size(size) + independent)
                hit, saving = refs.observe(payload, baseline)
                group["ref_eligible"] += int(8 <= size <= self.args.ref_max_entry)
                group["ref_hits"] += int(hit)
                group["ref_saved_bytes_est"] += saving
                digest = hashlib.blake2b(payload, digest_size=16).digest()
                if digest_count < self.args.max_digests:
                    inserted = self.db.execute("INSERT OR IGNORE INTO digests VALUES (?,?)", (gid, digest)).rowcount
                    digest_count += inserted
                    group["dup_samples"] += 1
                    group["dup_hits"] += 1 - inserted
                else:
                    self.issue("digest_budget", "dup_any incomplete after digest budget; reported ratio is null")
                old = previous.pop(gid, None)
                if old is not None:
                    previous_bytes -= len(old)
                    prefix, suffix, limit = 0, 0, min(len(old), size)
                    while prefix < limit and old[prefix] == payload[prefix]:
                        prefix += 1
                    while suffix < limit - prefix and old[-suffix - 1] == payload[-suffix - 1]:
                        suffix += 1
                    group["delta_pairs"] += 1
                    group["delta_changed_bytes"] += size - prefix - suffix
                    group["delta_target_bytes"] += size
                while previous and previous_bytes + size > 16 * 1024 * 1024:
                    _, evicted = previous.popitem(last=False)
                    previous_bytes -= len(evicted)
                    self.issue("delta_budget", "delta pairs omitted after previous-payload budget")
                if size <= 16 * 1024 * 1024:
                    previous[gid] = payload
                    previous_bytes += size
                for duration, name in ((50_000_000, "burst_max_per_50ms"), (1_000_000_000, "burst_max_per_1s")):
                    queue, byte_count = windows.setdefault((gid, duration), (deque(), 0))
                    while queue and elapsed - queue[0][0] >= duration:
                        byte_count -= queue.popleft()[1]
                        window_records -= 1
                    if window_records >= 65536:
                        for window_key, (other_queue, other_bytes) in list(windows.items()):
                            if window_key == (gid, duration):
                                continue
                            while other_queue and elapsed - other_queue[0][0] >= window_key[1]:
                                other_bytes -= other_queue.popleft()[1]
                                window_records -= 1
                            windows[window_key] = (other_queue, other_bytes)
                    if window_records >= 65536:
                        raise ValueError("burst window budget exceeded; analysis stopped")
                    queue.append((elapsed, size))
                    byte_count += size
                    window_records += 1
                    windows[(gid, duration)] = (queue, byte_count)
                    group[name]["count"] = max(group[name]["count"], len(queue))
                    group[name]["bytes"] = max(group[name]["bytes"], byte_count)
                if not self.args.no_split_join:
                    joined = splitter.accept(record)
                    if joined:
                        content = self.count(joined, "reassembled")
                        totals[content["_id"]] = totals.get(content["_id"], 0) + joined["payload_length"]
            splitter.reset("end of connection")
            if stream is not None:
                self.stream_tail += len(stream.flush(zstandard.COMPRESSOBJ_FLUSH_FINISH))
            for group in self.groups.values():
                group["per_connection_bytes_max"] = max(group["per_connection_bytes_max"], totals.get(group["_id"], 0))
            self.db.commit()
        return self.results()

    def results(self):
        totals = {view: sum(g["bytes"] for g in self.groups.values() if g["view"] == view) for view in ("raw", "reassembled")}
        rows = []
        for group in self.groups.values():
            row = {k: v for k, v in group.items() if not k.startswith("_")}
            row["bytes_share"] = group["bytes"] / totals[group["view"]] if totals[group["view"]] else 0
            for percent in (50, 90, 99, 100):
                offset = max(0, math.ceil(group["count"] * percent / 100) - 1)
                value = self.db.execute("SELECT size FROM sizes WHERE g=? ORDER BY size LIMIT 1 OFFSET ?", (group["_id"], offset)).fetchone()[0]
                row["size_max" if percent == 100 else f"size_p{percent}"] = value
            if group["view"] == "raw":
                row["dup_any_ratio"] = group["dup_hits"] / group["count"] if group["dup_samples"] == group["count"] else None
                row["ref_eligible_dup_ratio"] = group["ref_hits"] / group["ref_eligible"] if group["ref_eligible"] else 0
                row["delta_changed_bytes_mean"] = group["delta_changed_bytes"] / group["delta_pairs"] if group["delta_pairs"] else None
                row["delta_changed_ratio"] = group["delta_changed_bytes"] / group["delta_target_bytes"] if group["delta_target_bytes"] else None
            else:
                for key in list(row):
                    if key.startswith(("dup_", "ref_", "zstd_", "delta_", "burst_")):
                        del row[key]
            rows.append(row)
        return sorted(rows, key=lambda r: (r["view"] != "raw", -r["bytes"]))


def run_analyze(files, args, discovery_diagnostics):
    from packbatch import Diagnostic, output_diagnostics
    analyzer, rows = None, []
    diagnostics = list(discovery_diagnostics)
    try:
        with tempfile.TemporaryDirectory(prefix="rdi-packet-analysis-", dir=args.work_dir) as directory:
            with closing(sqlite3.connect(Path(directory) / "spool.db")) as db:
                analyzer = Analyzer(db, args)
                analyzer.ingest(files)
                rows = analyzer.process()
    except (ValueError, OSError, sqlite3.Error, zstandard.ZstdError) as exc:
        diagnostics.append(Diagnostic("analysis_failed", str(exc)))
    if analyzer:
        diagnostics.extend(Diagnostic(code, message, details={"occurrences": count})
                           for (code, message), count in analyzer.diagnostics.items())
    metadata = dict(kind="analysis_model", complete=not diagnostics, raw_bytes=sum(r["bytes"] for r in rows if r["view"] == "raw"),
        group=args.group, level=3, window_log=25, threshold=args.threshold, ref_window=args.ref_window,
        ref_max_entry=args.ref_max_entry, reference_start_bytes=analyzer.start_cost if analyzer else 0,
        stream_finish_bytes=analyzer.stream_tail if analyzer else 0,
        note="Captured subset simulations; neither actual wire savings nor a bound. Raw and reassembled views must not be added. Models reset at phase changes and discontinuities. START and stream finish costs are separate.")
    if args.json:
        for row in rows[:args.top]:
            print(json.dumps(dict(kind="group", analysis_complete=not diagnostics, **row), separators=(",", ":")))
    else:
        print("view\tgroup\tcount\tbytes\tp99")
        for row in rows[:args.top]:
            print(f"{row['view']}\t{','.join(str(row[f]) for f in args.group.split(','))}\t{row['count']}\t{row['bytes']}\t{row['size_p99']}")
    print(json.dumps(metadata, separators=(",", ":")), file=sys.stderr)
    output_diagnostics(diagnostics)
    return 1 if diagnostics else 0
