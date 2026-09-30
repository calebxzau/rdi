import contextlib
import io
import json
import struct
import tempfile
import unittest
import uuid
from pathlib import Path

import zstandard

import packbatch


RUN = uuid.UUID("0192a4d0-7b10-7000-8000-000000000001")
PLAYER_A = uuid.UUID("0192a4d0-7b10-7000-8000-000000000002")
PLAYER_B = uuid.UUID("0192a4d0-7b10-7000-8000-000000000003")


def string(value):
    encoded = value.encode("utf-8")
    return struct.pack(">H", len(encoded)) + encoded


def header(part, started_ms=1_727_000_000_000):
    return b"\x01RDPC" + struct.pack(">H", 1) + RUN.bytes + struct.pack(">qI", started_ms, part)


def record(player=PLAYER_A, connection=1, sequence=1, elapsed=10, packet_type="minecraft:custom_payload",
           channel="example:channel", payload=b"\x7fhello"):
    return (struct.pack(">q", elapsed) + player.bytes + struct.pack(">QQ", connection, sequence) +
            string(packet_type) + string(channel) + struct.pack(">I", len(payload)) + payload)


def data_frame(*records):
    return b"\x02" + struct.pack(">I", len(records)) + b"".join(records)


def footer(count, dropped, final=False, elapsed=100):
    return b"\x03" + struct.pack(">QQBq", count, dropped, int(final), elapsed)


def zframe(body):
    return zstandard.ZstdCompressor(level=3, write_checksum=True, write_content_size=True).compress(body)


def capture_bytes(part, records, dropped=0, final=False):
    return b"".join([zframe(header(part)), *(zframe(data_frame(item)) for item in records),
                     zframe(footer(len(records), dropped, final))])


class PackbatchTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.directory = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def write(self, name, content):
        path = self.directory / name
        path.write_bytes(content)
        return path

    def test_exact_binary_fields_and_payload(self):
        path = self.write("capture.rdibatch.zst", capture_bytes(1, [record(payload=b"\x00\xffpacket")], final=True))
        diagnostics = []
        decoded = list(packbatch.read_file(path, diagnostics))
        self.assertEqual([], diagnostics)
        self.assertEqual({
            "run_id": str(RUN), "run_started_ms": 1_727_000_000_000, "part": 1,
            "elapsed_ns": 10, "player": str(PLAYER_A), "connection_id": 1, "sequence": 1,
            "type": "minecraft:custom_payload", "channel": "example:channel",
            "payload_length": 8, "_payload": b"\x00\xffpacket",
        }, decoded[0])

    def test_partial_file_salvages_complete_data_frames_and_reports_truncation(self):
        content = capture_bytes(1, [record()])
        path = self.write("capture.rdibatch.zst.partial", content[:-3])
        diagnostics = []
        self.assertEqual(1, len(list(packbatch.read_file(path, diagnostics))))
        self.assertIn(diagnostics[0].code, {"corrupt_frame", "missing_footer"})

    def test_corrupt_frame_after_good_record_is_reported(self):
        content = zframe(header(1)) + zframe(data_frame(record())) + b"broken"
        path = self.write("bad.rdibatch.zst", content)
        diagnostics = []
        self.assertEqual(1, len(list(packbatch.read_file(path, diagnostics))))
        self.assertEqual("corrupt_frame", diagnostics[0].code)

    def test_malformed_first_frame_is_reported_without_raising(self):
        path = self.write("malformed.rdibatch.zst.partial", b"broken")
        diagnostics = []
        self.assertEqual([], list(packbatch.read_file(path, diagnostics)))
        self.assertEqual("corrupt_frame", diagnostics[0].code)

    def test_multipart_order_gap_and_max_cumulative_drops(self):
        first = self.write("random-name-a.rdibatch.zst", capture_bytes(1, [record(sequence=1)], dropped=7))
        third = self.write("random-name-c.rdibatch.zst", capture_bytes(3, [record(sequence=3)], dropped=12, final=True))
        diagnostics = []
        files = packbatch.ordered_files([str(third), str(first)], diagnostics)
        summary, read_diagnostics = packbatch.run_summary(files, 20)
        self.assertEqual([1, 3], [header.part for _, header in files])
        self.assertIn("missing_part", [item.code for item in diagnostics])
        self.assertIn("sequence_gap", [item.code for item in read_diagnostics])
        self.assertEqual({str(RUN): 12}, summary["dropped_records_by_run"])

    def test_missing_part_range_does_not_expand_large_gaps(self):
        first = self.write("part-one.rdibatch.zst", capture_bytes(1, [], dropped=0))
        last_part = 4_294_967_295
        last = self.write("part-last.rdibatch.zst", capture_bytes(last_part, [], dropped=0, final=True))
        diagnostics = []
        packbatch.ordered_files([str(first), str(last)], diagnostics)
        missing = [item for item in diagnostics if item.code == "missing_part"]
        self.assertEqual(1, len(missing))
        self.assertEqual({"first_missing": 2, "last_missing": last_part - 1}, missing[0].details)

    def test_summary_uses_absolute_min_max_timestamps(self):
        path = self.write("out-of-order.rdibatch.zst", capture_bytes(1, [
            record(sequence=1, elapsed=2_000_000),
            record(sequence=2, elapsed=1_000_000),
        ], final=True))
        summary, diagnostics = packbatch.run_summary([(path, packbatch.probe_header(path))], 10)
        self.assertEqual([], diagnostics)
        group = summary["groups"][0]
        self.assertEqual(1_727_000_000_001, group["first_timestamp_ms"])
        self.assertEqual(1_727_000_000_002, group["last_timestamp_ms"])

    def test_invalid_file_footer_is_not_counted_as_a_drop_snapshot(self):
        path = self.write("bad-footer.rdibatch.zst", b"".join([
            zframe(header(1)), zframe(data_frame(record())), zframe(footer(99, 500, final=True)),
        ]))
        summary, diagnostics = packbatch.run_summary([(path, packbatch.probe_header(path))], 10)
        self.assertIn("invalid_record", [item.code for item in diagnostics])
        self.assertEqual({}, summary["dropped_records_by_run"])

    def test_sequence_tracker_caps_connection_memory_and_reports_limit(self):
        path = self.write("many-connections.rdibatch.zst", capture_bytes(1, [
            record(connection=1, sequence=1), record(connection=2, sequence=1),
            record(connection=2, sequence=2),
        ], final=True))
        original_limit = packbatch.MAX_TRACKED_CONNECTIONS
        try:
            packbatch.MAX_TRACKED_CONNECTIONS = 1
            _, diagnostics = packbatch.run_summary([(path, packbatch.probe_header(path))], 10)
        finally:
            packbatch.MAX_TRACKED_CONNECTIONS = original_limit
        limit = next(item for item in diagnostics if item.code == "sequence_tracking_limit")
        self.assertEqual(2, limit.details["untracked_records"])

    def test_dump_filters_limit_and_optional_hex_payload(self):
        path = self.write("records.rdibatch.zst", capture_bytes(1, [
            record(sequence=1, payload=b"one"),
            record(player=PLAYER_B, connection=2, sequence=1, channel="example:other", payload=b"two"),
            record(sequence=2, elapsed=20, payload=b"three"),
        ], final=True))
        files = [(path, packbatch.probe_header(path))]
        args = type("Args", (), {"player": str(PLAYER_A), "channel": "example:channel", "type": None,
                                  "since": 10, "until": 20, "limit": 1, "payload": "hex"})
        stdout = io.StringIO()
        stderr = io.StringIO()
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            diagnostics, status = packbatch.run_dump(files, args)
        rows = [json.loads(line) for line in stdout.getvalue().splitlines()]
        self.assertEqual(1, len(rows))
        self.assertEqual("6f6e65", rows[0]["payload"])
        self.assertEqual([], diagnostics)
        self.assertTrue(status["limit_reached"])
        self.assertEqual(2, status["matched"])

    def test_summary_group_overflow_is_bounded(self):
        path = self.write("groups.rdibatch.zst", capture_bytes(1, [
            record(sequence=1, channel="example:a"),
            record(sequence=2, channel="example:b"),
            record(sequence=3, channel="example:c"),
        ], final=True))
        summary, diagnostics = packbatch.run_summary([(path, packbatch.probe_header(path))], 2)
        self.assertEqual([], diagnostics)
        self.assertEqual(2, len(summary["groups"]))
        overflow = next(group for group in summary["groups"] if group["player"] == "<overflow>")
        self.assertEqual(2, overflow["count"])

    def test_cli_returns_nonzero_for_truncation(self):
        path = self.write("truncated.rdibatch.zst.partial", capture_bytes(1, [record()])[:-2])
        stderr = io.StringIO()
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(stderr):
            code = packbatch.main(["dump", str(path)])
        self.assertEqual(1, code)
        self.assertIn('"kind":"diagnostic"', stderr.getvalue())

    def test_cli_returns_nonzero_for_corrupt_complete_file(self):
        path = self.write("corrupt.rdibatch.zst", zframe(header(1)) + b"not-a-frame")
        stderr = io.StringIO()
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(stderr):
            code = packbatch.main(["dump", str(path)])
        self.assertEqual(1, code)
        self.assertIn("corrupt_frame", stderr.getvalue())

    def test_cli_reports_missing_input_as_structured_diagnostics(self):
        missing = self.directory / "missing.rdibatch.zst"
        stderr = io.StringIO()
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(stderr):
            code = packbatch.main(["dump", str(missing)])
        output = [json.loads(line) for line in stderr.getvalue().splitlines()]
        self.assertEqual(1, code)
        diagnostics = [row for row in output if row.get("kind") == "diagnostic"]
        self.assertIn("unreadable_header", [row["code"] for row in diagnostics])
        self.assertIn("io_error", [row["code"] for row in diagnostics])
        self.assertTrue(all(isinstance(row, dict) for row in output))

    def test_cli_reports_truncated_first_frame_as_structured_diagnostic(self):
        path = self.write("truncated-header.rdibatch.zst.partial", b"\x28\xb5\x2f")
        stderr = io.StringIO()
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(stderr):
            code = packbatch.main(["dump", str(path)])
        output = [json.loads(line) for line in stderr.getvalue().splitlines()]
        self.assertEqual(1, code)
        diagnostic = next(row for row in output if row.get("code") == "corrupt_frame")
        self.assertEqual("corrupt_frame", diagnostic["code"])
        self.assertEqual(str(path), diagnostic["path"])


if __name__ == "__main__":
    unittest.main()
