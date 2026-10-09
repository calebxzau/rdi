#!/usr/bin/env python3
"""Bounded streaming reader for the RDPC packet capture format."""

from __future__ import annotations

import argparse
import base64
import json
import struct
import sys
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import BinaryIO, Iterable, Iterator

try:
    import zstandard
except ImportError as exc:  # pragma: no cover - exercised by command-line users
    raise SystemExit("Missing dependency: install requirements.txt (zstandard)") from exc


VERSION = 2
MAX_FILE_SIZE = 128_000_000
MAX_COMPRESSED_FRAME = 10 * 1024 * 1024
MAX_DECOMPRESSED_FRAME = 9 * 1024 * 1024
MAX_STRING_BYTES = 4096
MAX_PAYLOAD_BYTES = 8 * 1024 * 1024
MAX_RECORDS_PER_FRAME = 32768
MAX_TRACKED_CONNECTIONS = 65536
FILE_SUFFIX = ".rdibatch.zst"
ZSTD_MAGIC = b"\x28\xb5\x2f\xfd"


class CaptureError(Exception):
    pass


@dataclass
class Diagnostic:
    code: str
    message: str
    path: str | None = None
    run_id: str | None = None
    part: int | None = None
    details: dict | None = None

    def as_json(self) -> dict:
        result = {"kind": "diagnostic", "code": self.code, "message": self.message}
        if self.path is not None:
            result["path"] = self.path
        if self.run_id is not None:
            result["run_id"] = self.run_id
        if self.part is not None:
            result["part"] = self.part
        if self.details is not None:
            result["details"] = self.details
        return result


@dataclass(frozen=True)
class Header:
    run_id: str
    started_ms: int
    part: int
    version: int = 1


@dataclass
class FileInfo:
    path: Path
    header: Header | None = None
    records: int = 0
    footer_records: int | None = None
    dropped: int | None = None
    final: bool | None = None
    elapsed_ns: int | None = None
    complete: bool = False


class Cursor:
    def __init__(self, data: bytes):
        self.data = data
        self.offset = 0

    def take(self, size: int) -> bytes:
        if size < 0 or self.offset + size > len(self.data):
            raise CaptureError("record is truncated")
        start = self.offset
        self.offset += size
        return self.data[start:self.offset]

    def unpack(self, fmt: str):
        size = struct.calcsize(fmt)
        return struct.unpack(fmt, self.take(size))

    def string(self) -> str:
        (size,) = self.unpack(">H")
        if size > MAX_STRING_BYTES:
            raise CaptureError(f"string length {size} exceeds {MAX_STRING_BYTES} bytes")
        try:
            return self.take(size).decode("utf-8", errors="strict")
        except UnicodeDecodeError as exc:
            raise CaptureError("string is not valid UTF-8") from exc


def _read_exact(stream: BinaryIO, size: int, label: str, frame_size: int) -> bytes:
    if frame_size + size > MAX_COMPRESSED_FRAME:
        raise CaptureError("compressed frame exceeds 10 MiB limit")
    data = stream.read(size)
    if len(data) != size:
        raise CaptureError(f"truncated Zstandard {label}")
    return data


def read_zstd_frame(stream: BinaryIO) -> bytes | None:
    """Read exactly one complete ordinary Zstandard frame, with input bounded."""
    magic = stream.read(4)
    if not magic:
        return None
    frame = bytearray(magic)
    if len(magic) != 4 or magic != ZSTD_MAGIC:
        raise CaptureError("invalid Zstandard frame magic")

    def take(size: int, label: str) -> bytes:
        data = _read_exact(stream, size, label, len(frame))
        frame.extend(data)
        return data

    (descriptor,) = take(1, "frame header")
    fcs_flag = descriptor >> 6
    single_segment = bool(descriptor & 0x20)
    checksum = bool(descriptor & 0x04)
    dict_flag = descriptor & 0x03
    if descriptor & 0x18:
        raise CaptureError("reserved Zstandard frame-header bits are set")
    if not checksum:
        raise CaptureError("Zstandard frame checksum is required")
    if not single_segment:
        (window_descriptor,) = take(1, "window descriptor")
        window_log = 10 + (window_descriptor >> 3)
        window_base = 1 << window_log
        window_size = window_base + (window_base >> 3) * (window_descriptor & 0x07)
        if window_size > MAX_DECOMPRESSED_FRAME:
            raise CaptureError("Zstandard window exceeds 9 MiB limit")
    dict_size = (0, 1, 2, 4)[dict_flag]
    if dict_size:
        take(dict_size, "dictionary ID")
    fcs_size = (1 if single_segment else 0) if fcs_flag == 0 else (2, 4, 8)[fcs_flag - 1]
    expected_size = None
    if fcs_size:
        raw_size = take(fcs_size, "content size")
        expected_size = int.from_bytes(raw_size, "little")
        if fcs_size == 2:
            expected_size += 256
        if expected_size > MAX_DECOMPRESSED_FRAME:
            raise CaptureError("declared decompressed frame exceeds 9 MiB limit")
    else:
        raise CaptureError("Zstandard frame must declare its decompressed content size")

    last_block = False
    while not last_block:
        raw_header = take(3, "block header")
        block_header = int.from_bytes(raw_header, "little")
        last_block = bool(block_header & 1)
        block_type = (block_header >> 1) & 0x03
        block_size = block_header >> 3
        if block_type == 3:
            raise CaptureError("reserved Zstandard block type")
        if block_size > 128 * 1024:
            raise CaptureError("Zstandard block exceeds 128 KiB")
        take(1 if block_type == 1 else block_size, "block data")
    if checksum:
        take(4, "frame checksum")

    try:
        decoded = zstandard.ZstdDecompressor().decompress(
            bytes(frame), max_output_size=MAX_DECOMPRESSED_FRAME, allow_extra_data=False
        )
    except zstandard.ZstdError as exc:
        raise CaptureError(f"Zstandard frame validation failed: {exc}") from exc
    if len(decoded) > MAX_DECOMPRESSED_FRAME:
        raise CaptureError("decompressed frame exceeds 9 MiB limit")
    if expected_size is not None and len(decoded) != expected_size:
        raise CaptureError("decompressed length does not match Zstandard frame header")
    return decoded


def parse_header(data: bytes) -> Header:
    cursor = Cursor(data)
    (kind,) = cursor.unpack(">B")
    if kind != 1 or cursor.take(4) != b"RDPC":
        raise CaptureError("first frame is not an RDPC header")
    (version,) = cursor.unpack(">H")
    if version not in (1, VERSION):
        raise CaptureError(f"unsupported capture version {version}")
    run_id = str(uuid.UUID(bytes=cursor.take(16)))
    (started_ms,) = cursor.unpack(">q")
    (part,) = cursor.unpack(">I")
    if part < 1:
        raise CaptureError("part number must start at 1")
    if cursor.offset != len(data):
        raise CaptureError("trailing bytes after header")
    return Header(run_id, started_ms, part, version)


def parse_record(cursor: Cursor, header: Header) -> dict:
    (elapsed_ns,) = cursor.unpack(">q")
    if elapsed_ns < 0:
        raise CaptureError("negative elapsed time")
    player = str(uuid.UUID(bytes=cursor.take(16)))
    (connection_id, sequence) = cursor.unpack(">QQ")
    if connection_id < 1 or sequence < 1:
        raise CaptureError("connection ID and sequence must be positive")
    phase = cursor.unpack(">B")[0] if header.version >= 2 else 0
    if phase not in (0, 1):
        raise CaptureError(f"unknown protocol phase {phase}")
    packet_type = cursor.string()
    channel = cursor.string()
    (payload_size,) = cursor.unpack(">I")
    if payload_size > MAX_PAYLOAD_BYTES:
        raise CaptureError(f"payload length {payload_size} exceeds 8 MiB limit")
    payload = cursor.take(payload_size)
    return {
        "run_id": header.run_id,
        "run_started_ms": header.started_ms,
        "part": header.part,
        "elapsed_ns": elapsed_ns,
        "player": player,
        "connection_id": connection_id,
        "sequence": sequence,
        "phase": "configuration" if phase else "play",
        "type": packet_type,
        "channel": channel,
        "payload_length": payload_size,
        "_payload": payload,
    }


def discover(paths: Iterable[str], diagnostics: list[Diagnostic]) -> list[Path]:
    found: dict[str, Path] = {}
    for input_path in paths:
        path = Path(input_path)
        try:
            candidates = path.iterdir() if path.is_dir() else (path,)
            for candidate in candidates:
                name = candidate.name
                if name.endswith(FILE_SUFFIX) or name.endswith(FILE_SUFFIX + ".partial"):
                    found[str(candidate.resolve())] = candidate
        except OSError as exc:
            diagnostics.append(Diagnostic("io_error", str(exc), str(path)))
    return list(found.values())


def read_file(path: Path, diagnostic_sink: list[Diagnostic], file_info_sink: list[FileInfo] | None = None) -> Iterator[dict]:
    def issue(code: str, message: str, header: Header | None = None):
        item = Diagnostic(code, message, str(path), header.run_id if header else None,
                          header.part if header else None)
        diagnostic_sink.append(item)

    try:
        if path.stat().st_size > MAX_FILE_SIZE:
            issue("file_too_large", "file exceeds 128,000,000-byte limit")
            return
        with path.open("rb") as stream:
            try:
                first = read_zstd_frame(stream)
            except (CaptureError, zstandard.ZstdError) as exc:
                issue("corrupt_frame", f"first frame: {exc}")
                return
            if first is None:
                issue("empty_file", "file contains no Zstandard frames")
                return
            try:
                header = parse_header(first)
            except CaptureError as exc:
                issue("invalid_header", str(exc))
                return
            info = FileInfo(path, header)
            if file_info_sink is not None:
                file_info_sink.append(info)
            frame_index = 1
            saw_footer = False
            while True:
                try:
                    frame = read_zstd_frame(stream)
                except (CaptureError, zstandard.ZstdError) as exc:
                    issue("corrupt_frame", str(exc), header)
                    return
                if frame is None:
                    break
                frame_index += 1
                if saw_footer:
                    issue("frame_after_footer", "footer is not the last frame", header)
                    return
                try:
                    cursor = Cursor(frame)
                    (kind,) = cursor.unpack(">B")
                    if kind == 1:
                        raise CaptureError("header frame appears after first frame")
                    if kind == 2:
                        (count,) = cursor.unpack(">I")
                        if count < 1 or count > MAX_RECORDS_PER_FRAME:
                            raise CaptureError(f"invalid record count {count}")
                        records = [parse_record(cursor, header) for _ in range(count)]
                        if cursor.offset != len(frame):
                            raise CaptureError("trailing bytes after data records")
                        for record in records:
                            info.records += 1
                            yield record
                    elif kind == 3:
                        if cursor.offset != 1:
                            raise CaptureError("invalid footer prefix")
                        (file_records, dropped, final) = cursor.unpack(">QQB")
                        (elapsed_ns,) = cursor.unpack(">q")
                        if final not in (0, 1) or elapsed_ns < 0 or cursor.offset != len(frame):
                            raise CaptureError("invalid footer fields")
                        if file_records != info.records:
                            raise CaptureError(f"footer record count {file_records} does not match {info.records}")
                        info.footer_records = file_records
                        info.dropped = dropped
                        info.final = bool(final)
                        info.elapsed_ns = elapsed_ns
                        saw_footer = True
                    else:
                        raise CaptureError(f"unknown frame kind {kind}")
                except (CaptureError, ValueError, struct.error) as exc:
                    issue("invalid_record", f"frame {frame_index}: {exc}", header)
                    return
            if not saw_footer:
                issue("missing_footer", "file ended without a footer", header)
                return
            info.complete = True
    except OSError as exc:
        issue("io_error", str(exc))


def probe_header(path: Path) -> Header | None:
    try:
        if path.stat().st_size > MAX_FILE_SIZE:
            return None
        with path.open("rb") as stream:
            frame = read_zstd_frame(stream)
        return parse_header(frame) if frame is not None else None
    except (OSError, CaptureError, zstandard.ZstdError):
        return None


def ordered_files(paths: Iterable[str], diagnostics: list[Diagnostic]) -> list[tuple[Path, Header | None]]:
    items = [(path, probe_header(path)) for path in discover(paths, diagnostics)]
    for path, header in items:
        if header is None:
            diagnostics.append(Diagnostic("unreadable_header", "cannot read a valid first-frame header", str(path)))
    items.sort(key=lambda item: (item[1].run_id if item[1] else "~", item[1].part if item[1] else 0,
                                 str(item[0]).casefold()))
    per_run: dict[str, list[tuple[Path, Header]]] = {}
    for path, header in items:
        if header:
            per_run.setdefault(header.run_id, []).append((path, header))
    for run_id, run_items in per_run.items():
        seen: set[int] = set()
        for path, header in run_items:
            if header.part in seen:
                diagnostics.append(Diagnostic("duplicate_part", "duplicate part number among supplied files",
                                              str(path), run_id, header.part))
            seen.add(header.part)
        sorted_parts = sorted(seen)
        for previous, current in zip(sorted_parts, sorted_parts[1:]):
            if current > previous + 1:
                diagnostics.append(Diagnostic(
                    "missing_part", f"missing parts {previous + 1}-{current - 1} among supplied files",
                    None, run_id, previous + 1,
                    {"first_missing": previous + 1, "last_missing": current - 1},
                ))
    return items


def parse_time_arg(value: str) -> int:
    try:
        result = int(value, 10)
    except ValueError as exc:
        raise argparse.ArgumentTypeError("timestamps are elapsed nanoseconds since run start") from exc
    if result < 0:
        raise argparse.ArgumentTypeError("elapsed nanoseconds must be non-negative")
    return result


def output_diagnostics(diags: list[Diagnostic]) -> None:
    for diagnostic in diags:
        print(json.dumps(diagnostic.as_json(), separators=(",", ":")), file=sys.stderr)


class SequenceTracker:
    def __init__(self):
        self.last_sequences: dict[tuple[str, int], int] = {}
        self.gap_count = 0
        self.gap_examples: list[dict] = []
        self.untracked_count = 0

    def observe(self, record: dict, path: Path) -> None:
        key = (record["run_id"], record["connection_id"])
        sequence = record["sequence"]
        if key not in self.last_sequences:
            if len(self.last_sequences) >= MAX_TRACKED_CONNECTIONS:
                self.untracked_count += 1
                return
            self.last_sequences[key] = sequence
            return
        prior = self.last_sequences[key]
        if sequence != prior + 1:
            self.gap_count += 1
            if len(self.gap_examples) < 5:
                self.gap_examples.append({"run_id": record["run_id"], "connection_id": record["connection_id"],
                                          "expected": prior + 1, "actual": sequence, "part": record["part"],
                                          "path": str(path)})
        self.last_sequences[key] = sequence

    def diagnostics(self) -> list[Diagnostic]:
        result = []
        if self.gap_count:
            result.append(Diagnostic("sequence_gap", f"detected {self.gap_count} connection sequence gaps",
                                     details={"count": self.gap_count, "examples": self.gap_examples}))
        if self.untracked_count:
            result.append(Diagnostic(
                "sequence_tracking_limit", "sequence checks skipped after reaching the tracked-connection limit",
                details={"tracked_connection_limit": MAX_TRACKED_CONNECTIONS,
                         "untracked_records": self.untracked_count},
            ))
        return result


def run_summary(files: list[tuple[Path, Header | None]], max_groups: int) -> tuple[dict, list[Diagnostic]]:
    diagnostics: list[Diagnostic] = []
    groups: dict[tuple, dict] = {}
    file_results = []
    dropped_by_run: dict[str, int] = {}
    sequence_tracker = SequenceTracker()
    overflow_key = ("<overflow>",) * 4
    for path, supplied_header in files:
        local_diags: list[Diagnostic] = []
        file_info: list[FileInfo] = []
        for record in read_file(path, local_diags, file_info):
            sequence_tracker.observe(record, path)
            key = (record["player"], record["phase"], record["type"], record["channel"])
            named_group_limit = max_groups - 1
            if key not in groups and (key == overflow_key or len(groups) >= named_group_limit):
                key = overflow_key
                if key not in groups:
                    groups[key] = {"player": "<overflow>", "phase": "<overflow>", "type": "<overflow>", "channel": "<overflow>",
                                   "count": 0, "raw_bytes": 0,
                                   "first_timestamp_ms": None, "last_timestamp_ms": None}
            group = groups.setdefault(key, {"player": key[0], "phase": key[1], "type": key[2], "channel": key[3],
                                             "count": 0, "raw_bytes": 0,
                                             "first_timestamp_ms": None, "last_timestamp_ms": None})
            group["count"] += 1
            group["raw_bytes"] += record["payload_length"]
            timestamp_ms = record["run_started_ms"] + record["elapsed_ns"] // 1_000_000
            if group["first_timestamp_ms"] is None or timestamp_ms < group["first_timestamp_ms"]:
                group["first_timestamp_ms"] = timestamp_ms
            if group["last_timestamp_ms"] is None or timestamp_ms > group["last_timestamp_ms"]:
                group["last_timestamp_ms"] = timestamp_ms
        diagnostics.extend(local_diags)
        info = file_info[0] if file_info else None
        header = info.header if info else supplied_header
        summary = {"path": str(path), "run_id": header.run_id if header else None,
                   "part": header.part if header else None, "complete": bool(info and info.complete)}
        if info and info.footer_records is not None:
            summary.update({"footer_records": info.footer_records, "dropped_snapshot": info.dropped,
                            "final": info.final})
            if info.complete and info.dropped is not None and info.header:
                dropped_by_run[info.header.run_id] = max(
                    dropped_by_run.get(info.header.run_id, 0), info.dropped
                )
        file_results.append(summary)
    diagnostics.extend(sequence_tracker.diagnostics())
    result = {"files": file_results, "groups": sorted(groups.values(), key=lambda row: (row["player"], row["phase"], row["type"], row["channel"])),
              "dropped_records_by_run": dropped_by_run}
    return result, diagnostics


def run_dump(files: list[tuple[Path, Header | None]], args) -> tuple[list[Diagnostic], dict]:
    diagnostics: list[Diagnostic] = []
    sequence_tracker = SequenceTracker()
    emitted = 0
    matched = 0
    for path, _ in files:
        local_diags: list[Diagnostic] = []
        for record in read_file(path, local_diags):
            sequence_tracker.observe(record, path)
            if getattr(args, "phase", None) and record["phase"] != args.phase:
                continue
            if args.player and record["player"].lower() != args.player.lower():
                continue
            if args.channel is not None and record["channel"] != args.channel:
                continue
            if args.type is not None and record["type"] != args.type:
                continue
            if args.since is not None and record["elapsed_ns"] < args.since:
                continue
            if args.until is not None and record["elapsed_ns"] > args.until:
                continue
            matched += 1
            if emitted < args.limit:
                payload = record.pop("_payload")
                if args.payload == "hex":
                    record["payload"] = payload.hex()
                elif args.payload == "base64":
                    record["payload"] = base64.b64encode(payload).decode("ascii")
                print(json.dumps(record, separators=(",", ":"), ensure_ascii=False))
                emitted += 1
        diagnostics.extend(local_diags)
    diagnostics.extend(sequence_tracker.diagnostics())
    return diagnostics, {"kind": "status", "matched": matched, "emitted": emitted,
                         "limit": args.limit, "limit_reached": matched > args.limit}


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Inspect RDPC packet capture files")
    subparsers = parser.add_subparsers(dest="command", required=True)
    summary = subparsers.add_parser("summary", help="summarize packets by player, type, and channel")
    summary.add_argument("paths", nargs="+", help="capture files or directories")
    summary.add_argument("--max-groups", type=int, default=10000)
    dump = subparsers.add_parser("dump", help="write filtered packet records as JSON Lines")
    dump.add_argument("paths", nargs="+", help="capture files or directories")
    dump.add_argument("--player")
    dump.add_argument("--phase", choices=("play", "configuration"))
    dump.add_argument("--channel")
    dump.add_argument("--type")
    dump.add_argument("--since", type=parse_time_arg, help="inclusive elapsed nanoseconds since run start")
    dump.add_argument("--until", type=parse_time_arg, help="inclusive elapsed nanoseconds since run start")
    dump.add_argument("--limit", type=int, default=100)
    dump.add_argument("--payload", choices=("none", "hex", "base64"), default="none")
    from packbatch_analyze import add_parser
    add_parser(subparsers)
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    if args.command == "summary" and args.max_groups < 1:
        parser.error("--max-groups must be at least 1")
    if args.command == "dump" and args.limit < 0:
        parser.error("--limit must be non-negative")
    if getattr(args, "since", None) is not None and getattr(args, "until", None) is not None and args.since > args.until:
        parser.error("--since must be less than or equal to --until")
    discovery_diagnostics: list[Diagnostic] = []
    files = ordered_files(args.paths, discovery_diagnostics)
    if not files:
        discovery_diagnostics.append(Diagnostic("no_files", "no packet capture files found"))
    if args.command == "summary":
        result, diagnostics = run_summary(files, args.max_groups)
        diagnostics = discovery_diagnostics + diagnostics
        result["diagnostic_count"] = len(diagnostics)
        print(json.dumps(result, separators=(",", ":"), ensure_ascii=False))
    elif args.command == "analyze":
        from packbatch_analyze import run_analyze
        return run_analyze(files, args, discovery_diagnostics)
    else:
        diagnostics, status = run_dump(files, args)
        diagnostics = discovery_diagnostics + diagnostics
        print(json.dumps(status, separators=(",", ":")), file=sys.stderr)
    output_diagnostics(diagnostics)
    return 1 if diagnostics else 0


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
