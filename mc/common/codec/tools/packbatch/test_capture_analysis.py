import contextlib
import io
import json
import os
import sqlite3
import struct
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace

import packbatch
from packbatch_analyze import Analyzer, ReferenceModel, SplitJoin, add_parser
from test_packbatch import RUN, PLAYER_A, record, header, data_frame, footer, zframe, capture_bytes


def vint(value):
    result = bytearray()
    while value > 127:
        result.append((value & 127) | 128)
        value >>= 7
    result.append(value)
    return bytes(result)


def custom(channel, body=b"", packet_id=7):
    encoded = channel.encode()
    return vint(packet_id) + vint(len(encoded)) + encoded + body


def split(state, piece):
    body = bytes([state]) + piece
    return custom("neoforge:split", vint(len(body)) + body)


def v2record(phase=0, **kwargs):
    data = record(**kwargs)
    return data[:40] + bytes([phase]) + data[40:]


def v2capture(records, run=RUN):
    h = bytearray(header(1))
    h[5:7] = struct.pack(">H", 2)
    h[7:23] = run.bytes
    return zframe(bytes(h)) + zframe(data_frame(*records)) + zframe(footer(len(records), 0, True))


def arguments(**kwargs):
    import argparse
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command")
    add_parser(sub)
    args = parser.parse_args(["analyze", "unused"])
    for k, v in kwargs.items():
        setattr(args, k, v)
    return args


class CaptureAnalysisTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def write(self, data, name="capture.rdibatch.zst"):
        path = self.root / name
        path.write_bytes(data)
        return path

    def analyze(self, paths, **kwargs):
        diagnostics = []
        files = packbatch.ordered_files([str(p) for p in paths], diagnostics)
        with contextlib.closing(sqlite3.connect(self.root / "analysis.db")) as db:
            analyzer = Analyzer(db, arguments(**kwargs))
            analyzer.ingest(files)
            rows = analyzer.process()
        return rows, analyzer, diagnostics

    def test_v1_and_v2_phase_and_dump_filter(self):
        old = self.write(capture_bytes(1, [record()], final=True), "v1.rdibatch.zst")
        self.assertEqual("play", list(packbatch.read_file(old, []))[0]["phase"])
        path = self.write(v2capture([v2record(phase=1), v2record(sequence=2)]))
        diagnostics = []
        self.assertEqual(["configuration", "play"], [r["phase"] for r in packbatch.read_file(path, diagnostics)])
        self.assertFalse(diagnostics)
        summary, _ = packbatch.run_summary([(path, packbatch.probe_header(path))], 10)
        self.assertEqual(2, len(summary["groups"]))
        out = io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(io.StringIO()):
            code = packbatch.main(["dump", str(path), "--phase", "configuration"])
        self.assertEqual(0, code)
        self.assertEqual("configuration", json.loads(out.getvalue())["phase"])

    def test_unknown_phase_invalidates_whole_data_frame(self):
        path = self.write(v2capture([v2record(), v2record(phase=9, sequence=2)]))
        diagnostics = []
        self.assertEqual([], list(packbatch.read_file(path, diagnostics)))
        self.assertEqual("invalid_record", diagnostics[0].code)
        self.assertIn("unknown protocol phase", diagnostics[0].message)

    def test_exact_percentiles_duplicates_delta_bursts_and_models(self):
        path = self.write(v2capture([v2record(sequence=i+1, elapsed=i * 20_000_000, payload=b"a"*20)
                                     for i in range(4)]))
        rows, analyzer, diagnostics = self.analyze([path])
        self.assertFalse(diagnostics)
        self.assertFalse(analyzer.diagnostics)
        row = rows[0]
        self.assertEqual((4, 80, 20), (row["count"], row["bytes"], row["size_p99"]))
        self.assertEqual(.75, row["dup_any_ratio"])
        self.assertEqual(.75, row["ref_eligible_dup_ratio"])
        self.assertEqual(0, row["delta_changed_bytes_mean"])
        self.assertEqual({"count": 3, "bytes": 60}, row["burst_max_per_50ms"])
        self.assertEqual({"count": 4, "bytes": 80}, row["burst_max_per_1s"])
        self.assertGreater(row["zstd_stream_bytes"], 0)
        self.assertGreater(analyzer.start_cost, 0)

    def test_split_length_prefix_and_separate_views(self):
        payload = custom("mod:large", b"x" * 250)
        path = self.write(v2capture([
            v2record(sequence=1, channel="neoforge:split", payload=split(1, payload[:130])),
            v2record(sequence=2, channel="neoforge:split", payload=split(2, payload[130:]))]))
        rows, analyzer, _ = self.analyze([path])
        self.assertFalse(analyzer.diagnostics)
        raw, joined = rows
        self.assertEqual("raw", raw["view"])
        self.assertEqual("neoforge:split", raw["channel"])
        self.assertEqual(("reassembled", "mod:large", len(payload)), (joined["view"], joined["channel"], joined["bytes"]))
        self.assertGreater(raw["bytes"], joined["bytes"])
        self.assertNotIn("burst_max_per_50ms", joined)

    def test_reassembled_non_custom_packet_is_not_parsed_as_channel(self):
        notices = []
        joiner = SplitJoin(1024, lambda *x: notices.append(x))
        base = dict(channel="neoforge:split", type="minecraft:custom_payload", elapsed_ns=0)
        self.assertIsNone(joiner.accept(dict(base, _payload=split(1, b"\x08"))))
        joined = joiner.accept(dict(base, _payload=split(2, b"\xff\xff\xff")))
        self.assertEqual("<unclassified>", joined["type"])
        self.assertFalse(notices)

    def test_split_gap_and_phase_change_never_join(self):
        payload = custom("mod:large", b"x"*20)
        path = self.write(v2capture([
            v2record(sequence=1, phase=1, channel="neoforge:split", payload=split(1, payload[:10])),
            v2record(sequence=3, phase=0, channel="neoforge:split", payload=split(2, payload[10:]))]))
        rows, analyzer, _ = self.analyze([path])
        self.assertTrue(all(r["view"] == "raw" for r in rows))
        self.assertIn("incomplete_split", [k[0] for k in analyzer.diagnostics])
        self.assertIn("sequence_gap", [k[0] for k in analyzer.diagnostics])

    def test_split_budget_bad_length_orphan_and_new_first(self):
        notices = []
        j = SplitJoin(3, lambda *x: notices.append(x))
        base = dict(channel="neoforge:split", type="minecraft:custom_payload", elapsed_ns=0)
        for payload in (split(0, b"a"), split(1, b"a"), split(1, b"b"), split(2, b"123"), custom("neoforge:split", b"\x7f\x01x")):
            j.accept(dict(base, _payload=payload))
        self.assertTrue(any("budget" in message for _, message in notices))
        self.assertTrue(any("length mismatch" in message for _, message in notices))
        self.assertTrue(any("new first" in message for _, message in notices))

    def test_run_and_connection_ids_are_isolated(self):
        import uuid
        first = self.write(v2capture([v2record(payload=b"a"*20)]), "first.rdibatch.zst")
        second = self.write(v2capture([v2record(payload=b"a"*20)], run=uuid.UUID(int=RUN.int+10)), "second.rdibatch.zst")
        rows, analyzer, _ = self.analyze([first, second])
        self.assertEqual(0, rows[0]["dup_any_ratio"])
        self.assertEqual(0, rows[0]["ref_eligible_dup_ratio"])
        self.assertEqual(20, rows[0]["per_connection_bytes_max"])
        self.assertFalse(analyzer.diagnostics)

    def test_reference_cache_is_connection_wide_and_slot_cost_changes(self):
        model = ReferenceModel(256, 1024)
        values = [i.to_bytes(2,"big") + b"x"*18 for i in range(129)]
        for payload in values:
            self.assertEqual((False, 0), model.observe(payload, 22))
        self.assertEqual((True, 15), model.observe(values[127], 22))
        self.assertEqual((True, 14), model.observe(values[128], 22))
        self.assertEqual((False, 0), model.observe(b"tiny", 10))

    def test_digest_cap_marks_ratio_incomplete(self):
        path = self.write(v2capture([v2record(sequence=i+1, payload=bytes([i])*20) for i in range(3)]))
        rows, analyzer, _ = self.analyze([path], max_digests=1)
        self.assertIsNone(rows[0]["dup_any_ratio"])
        self.assertIn("digest_budget", [k[0] for k in analyzer.diagnostics])

    def test_group_budget_reports_failure_instead_of_complete_partial_table(self):
        path = self.write(v2capture([v2record(channel="m:a"), v2record(sequence=2, channel="m:b")]))
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = packbatch.main(["analyze", str(path), "--max-groups", "1", "--json"])
        self.assertEqual(1, code)
        self.assertEqual("", out.getvalue())
        self.assertIn('"complete":false', err.getvalue())
        self.assertIn("group budget", err.getvalue())

    def test_cli_analysis_json_and_invalid_limits(self):
        path = self.write(v2capture([v2record(payload=b"x"*20)]))
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = packbatch.main(["analyze", str(path), "--json"])
        self.assertEqual(0, code)
        self.assertEqual("group", json.loads(out.getvalue())["kind"])
        self.assertIn("neither actual wire savings", err.getvalue())
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(1, packbatch.main(["analyze", str(path), "--ref-window", "0"]))

    def test_missing_part_or_corrupt_tail_invalidates_pending_chain(self):
        payload = custom("mod:large", b"x" * 20)
        first = self.write(capture_bytes(1, [record(sequence=1, channel="neoforge:split", payload=split(1, payload[:10]))]), "first.rdibatch.zst")
        last = self.write(capture_bytes(3, [record(sequence=2, channel="neoforge:split", payload=split(2, payload[10:]))], final=True), "last.rdibatch.zst")
        rows, analyzer, discovery = self.analyze([first, last])
        self.assertIn("missing_part", [d.code for d in discovery])
        self.assertTrue(all(r["view"] == "raw" for r in rows))
        self.assertIn("incomplete_split", [k[0] for k in analyzer.diagnostics])

    def test_corrupt_tail_invalidates_chain_even_when_next_sequence_is_contiguous(self):
        payload = custom("mod:large", b"x" * 20)
        first_data = zframe(header(1)) + zframe(data_frame(record(sequence=1, channel="neoforge:split", payload=split(1, payload[:10])))) + b"broken"
        first = self.write(first_data, "first.rdibatch.zst.partial")
        last = self.write(capture_bytes(2, [record(sequence=2, channel="neoforge:split", payload=split(2, payload[10:]))], final=True), "last.rdibatch.zst")
        rows, analyzer, _ = self.analyze([first, last])
        self.assertTrue(all(r["view"] == "raw" for r in rows))
        self.assertIn("corrupt_frame", [k[0] for k in analyzer.diagnostics])
        self.assertIn("incomplete_split", [k[0] for k in analyzer.diagnostics])

    def test_database_budget_returns_structured_failure(self):
        path = self.write(v2capture([v2record(payload=b"x" * (2 * 1024 * 1024))]))
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = packbatch.main(["analyze", str(path), "--json", "--max-spool-bytes", "1048576"])
        self.assertEqual(1, code)
        self.assertEqual("", out.getvalue())
        self.assertIn("analysis_failed", err.getvalue())

    def test_shared_lru_across_groups_evicts_other_channels(self):
        a, b = custom("m:a", b"a"*10), custom("m:b", b"b"*10)
        path = self.write(v2capture([
            v2record(sequence=1, channel="m:a", payload=a),
            v2record(sequence=2, channel="m:b", payload=b),
            v2record(sequence=3, channel="m:a", payload=a)]))
        rows, _, _ = self.analyze([path], ref_window=1)
        group = next(r for r in rows if r["channel"] == "m:a")
        self.assertEqual(.5, group["dup_any_ratio"])
        self.assertEqual(0, group["ref_eligible_dup_ratio"])

    def test_kotlin_fixture_if_explicitly_requested(self):
        directory = os.environ.get("RDI_PACKBATCH_FIXTURE_DIR")
        if directory is None:
            self.skipTest("set RDI_PACKBATCH_FIXTURE_DIR for Kotlin-to-Python verification")
        root = Path(directory)
        manifest = json.loads((root / "fixture-manifest.json").read_text())
        diagnostics = []
        records = list(packbatch.read_file(root / manifest["part"], diagnostics))
        self.assertFalse(diagnostics)
        self.assertEqual(1, len(records))
        row = records[0]
        for key, expected in (("run_id", "run_id"), ("player", "player_id"), ("connection_id", "connection_id"),
                              ("sequence", "sequence"), ("phase", "phase"), ("type", "packet_type"), ("channel", "channel")):
            self.assertEqual(manifest[expected], row[key])
        self.assertEqual(manifest["payload_hex"], row["_payload"].hex())


if __name__ == "__main__":
    unittest.main()
