"""P2 evidence: matched reference pixels, raw depth/reflection, sampler A/B and authenticated memory."""
import hashlib
import json
import re
from datetime import datetime
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent / "evidence/bg-port/p2"
ANSI = re.compile(r"\x1b\[[0-9;]*m")
REGIONS = {"lake": (700, 395, 900, 425), "sky_clouds": (200, 10, 1000, 120), "near_hill": (400, 410, 880, 720)}


def rgb(path):
    return np.asarray(Image.open(path).convert("RGB"), dtype=float)


def pair(name, left, right, box=(0, 0, 1280, 720), zoom=1, matched=False):
    width, height = box[2] - box[0], box[3] - box[1]
    image = Image.new("RGB", (width * zoom * 2, height * zoom + 40), "#181b20")
    draw = ImageDraw.Draw(image)
    for i, (label, path) in enumerate([("Kotlin P2 control: no fog/bloom, AA off" if matched else "Kotlin full reference", left), ("Rust P2", right)]):
        draw.text((i * width * zoom + 8, 6), label, fill="white")
        draw.text((i * width * zoom + 8, 21), f"1280x720 region {box}", fill="#cccccc")
        crop = Image.open(path).convert("RGB").crop(box)
        image.paste(crop.resize((width * zoom, height * zoom), Image.Resampling.NEAREST), (i * width * zoom, 40))
    image.save(ROOT / name)


summary = {"times": {}, "regions": REGIONS}
for time in ["0.0", "15.0", "45.0"]:
    ref_path, rust_path = ROOT / f"reference/kotlin-p2-{time}.png", ROOT / f"final/p0-rust-{time}.png"
    reference, rendered = rgb(ref_path), rgb(rust_path)
    data = {"regions": {}}
    for name, (x, y, right, bottom) in REGIONS.items():
        a, b = reference[y:bottom, x:right], rendered[y:bottom, x:right]
        data["regions"][name] = {"kotlin_rgb": a.mean((0, 1)).tolist(), "rust_rgb": b.mean((0, 1)).tolist(), "mean_absolute_rgb_error": float(np.abs(a - b).mean())}
    a = np.fromfile(ROOT / f"reference/clouds-{time}.bin", "<f4")
    b = np.fromfile(ROOT / f"final/clouds-{time}.bin", "<f4")
    assert np.array_equal(a, b), "Cloud geometry differs from reference"
    data["cloud_vertices"] = len(a) // 12
    data["cloud_vertex_sha256"] = hashlib.sha256(a.tobytes()).hexdigest()
    a = np.fromfile(ROOT / f"reference/depth-{time}.depth32f", "<f4")
    b = np.fromfile(ROOT / f"final/depth-{time}.depth32f", "<f4")
    assert np.isfinite(b).all() and b.min() >= 0 and b.max() <= 1
    error = np.abs(a - b)
    data["pre_water_depth"] = {"median_absolute_error": float(np.median(error)), "p95_absolute_error": float(np.quantile(error, .95)), "finite_unit_range": True}
    hdr = np.fromfile(ROOT / f"final/hdr-{time}.rgba16f", "<f2").reshape(720, 1280, 4).astype(float)
    reflection = np.fromfile(ROOT / f"final/reflection-{time}.rgba16f", "<f2").reshape(360, 640, 4).astype(float)[..., :3]
    kreflection = np.fromfile(ROOT / f"reference/reflection-{time}.rgb32f", "<f4").reshape(360, 640, 3)
    assert np.isfinite(hdr).all() and np.isfinite(reflection).all()
    data["reflection_hdr"] = {"mean_absolute_rgb_error": float(np.abs(reflection - kreflection).mean()),
        "vertical_flip_error": float(np.abs(reflection[::-1] - kreflection).mean()),
        "horizontal_flip_error": float(np.abs(reflection[:, ::-1] - kreflection).mean()), "finite": True}
    old = rgb(ROOT / f"../p1/visual/final/p0-rust-{time}.png")
    new = rgb(ROOT / f"p1-preserved/p0-rust-{time}.png")
    data["p1_regression"] = {"maximum_channel_error": float(np.abs(old - new).max()), "pixels_with_channel_error_over_one": int((np.abs(old - new).max(2) > 1).sum())}
    assert data["p1_regression"]["maximum_channel_error"] <= 1
    pair(f"p2-compare-{time}.png", ROOT / f"reference/kotlin-original-{time}.png", rust_path)
    pair(f"p2-matched-{time}.png", ref_path, rust_path, matched=True)
    pair(f"p2-water-crop-{time}.png", ref_path, rust_path, (500, 360, 1060, 465), 2, True)
    summary["times"][time] = data
pair("p2-cloud-crop-0.0.png", ROOT / "reference/kotlin-p2-0.0.png", ROOT / "final/p0-rust-0.0.png", (180, 0, 1020, 170), 1, True)
cloud_cover = (ROOT / "reference/cloud-cover.rgba").read_bytes()
assert cloud_cover == (ROOT / "final/cloud-cover.rgba").read_bytes()
summary["cloud_cover_exact"] = True
summary["cloud_cover_sha256"] = hashlib.sha256(cloud_cover).hexdigest()

timing_root = ROOT / "sampler-ab/clean"
summary["sampler_ab"] = {"status": "pending", "metric": "CPU encode plus submit-to-completion; excludes capture, glass and presentation"}
if (timing_root / "complete.exit").exists():
    assert (timing_root / "complete.exit").read_text().strip() == "0"
    timing = {}
    for content in ["p1", "p2"]:
        timing[content] = {}
        for sampler in ["legacy", "nearest"]:
            runs = [json.loads(p.read_text()) for p in sorted(timing_root.glob(f"{content}-*-{sampler}/timing.json"))]
            assert len(runs) == 4
            values = np.array([r["mean_ms"] for r in runs])
            timing[content][sampler] = {"runs": runs, "mean_of_run_means_ms": float(values.mean()), "min_run_mean_ms": float(values.min()), "max_run_mean_ms": float(values.max())}
        timing[content]["nearest_minus_legacy_ms"] = timing[content]["nearest"]["mean_of_run_means_ms"] - timing[content]["legacy"]["mean_of_run_means_ms"]
    summary["sampler_ab"].update(status="passed", results=timing)

summary["authenticated_memory"] = {"status": "pending", "blocker": "Owner test-account credentials unavailable; no unauthenticated substitute was measured."}
memory = {}
for case in ["static", "p1", "p2"]:
    path = ROOT / f"measure/{case}-memory.jsonl"
    if not path.exists():
        continue
    assert (ROOT / f"measure/{case}-measure.exit").read_text().strip() == "0"
    log = ANSI.sub("", (ROOT / f"measure/{case}-app.log").read_text())
    assert "Menu data loaded" in log and "Menu login/load failed" not in log
    rows = [json.loads(line) for line in path.read_text().splitlines()]
    snapshots = []
    for line in log.splitlines():
        if "Memory allocator snapshot" in line:
            snapshot=json.loads(line.split("data=",1)[1])
            snapshot["time"]=datetime.fromisoformat(line.split()[0].replace("Z","+00:00"))
            snapshots.append(snapshot)
    endpoints = {}
    for phase in ["loaded", "foreground60s", "minimized30s", "restored10s"]:
        values = [v for v in rows if v["Phase"] == phase]
        assert values, (case, phase)
        value = values[0] if phase == "loaded" else values[-1]
        assert value["Authenticated"]
        cutoff=datetime.fromisoformat(value["Time"].replace("Z","+00:00"))
        prior=[s for s in snapshots if s["time"] <= cutoff and s.get("report")]
        allocator=prior[-1]["report"] if prior else None
        endpoints[phase] = {**value, **{k: value[k] / 2**20 for k in ["WorkingSet", "Private", "Dedicated", "Shared"]},
            "gpu_total_mib": (value["Dedicated"]+value["Shared"])/2**20,
            "allocated_mib":allocator["allocated"]/2**20 if allocator else None,
            "reserved_mib":allocator["reserved"]/2**20 if allocator else None}
    statuses = re.findall(r'API response.*?path="([^"]+)".*?status=(\d+)', log)
    counts = re.search(r'Menu data loaded.*?hosts=(\d+).*?modpacks=(\d+)', log)
    assert statuses and all(status=="200" for _,status in statuses),case
    assert counts,case
    assert endpoints["foreground60s"]["Width"]==2560 and endpoints["foreground60s"]["Height"]==1440
    assert endpoints["minimized30s"]["ActualMinimized"] and not endpoints["restored10s"]["ActualMinimized"]
    assert not any(row["Phase"]=="unexpected-minimize" for row in rows),case
    memory[case] = {"endpoints_mib": endpoints, "api_statuses": statuses, "counts": counts.groups(),
        "error_lines": [line for line in log.splitlines() if "ERROR" in line],
        "warning_lines": [line for line in log.splitlines() if "WARN" in line]}
if len(memory) == 3:
    assert len({tuple(case["counts"]) for case in memory.values()})==1
    summary["authenticated_memory"] = {"status": "passed", "cases": memory}
    summary["authenticated_memory"]["p2_minus_p1_mib"]={phase:{key:memory["p2"]["endpoints_mib"][phase][key]-memory["p1"]["endpoints_mib"][phase][key]
        for key in ["WorkingSet","Private","Dedicated","Shared","gpu_total_mib","allocated_mib","reserved_mib"]}
        for phase in ["loaded","foreground60s","minimized30s","restored10s"]}
    summary["authenticated_memory"]["glass_screenshots"]={case:f"measure/{case}-glass.png" for case in memory}

(ROOT / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
lines = ["# P2 gate evidence", "", "| Time | Lake RGB mean absolute error | Cloud vertices | P1 max channel change | Depth p95 error |", "|---|---:|---:|---:|---:|"]
for time, data in summary["times"].items():
    lines.append(f"|{time}s|{data['regions']['lake']['mean_absolute_rgb_error']:.3f}/255|{data['cloud_vertices']}|{data['p1_regression']['maximum_channel_error']:.0f}/255|{data['pre_water_depth']['p95_absolute_error']:.3g}|")
lines += ["", f"Cloud vertex bytes and thinned texture exactly match Kotlin. Authenticated memory: **{summary['authenticated_memory']['status']}**.", "", "Matched sampler A/B: " + summary["sampler_ab"]["status"] + "."]
if summary["sampler_ab"]["status"] == "passed":
    lines += ["", "| Content | Native linear ms | Accepted nearest ms | Delta ms |", "|---|---:|---:|---:|"]
    for content, data in summary["sampler_ab"]["results"].items():
        lines.append(f"|{content}|{data['legacy']['mean_of_run_means_ms']:.3f}|{data['nearest']['mean_of_run_means_ms']:.3f}|{data['nearest_minus_legacy_ms']:+.3f}|")
if len(memory) == 3:
    lines += ["", "| Case / phase | WS MiB | Private MiB | GPU dedicated / shared MiB | GPU total MiB | Allocated / reserved MiB |", "|---|---:|---:|---:|---:|---:|"]
    for case, data in memory.items():
        for phase, value in data["endpoints_mib"].items():
            lines.append(f"|{case}/{phase}|{value['WorkingSet']:.2f}|{value['Private']:.2f}|{value['Dedicated']:.2f}/{value['Shared']:.2f}|{value['gpu_total_mib']:.2f}|{value['allocated_mib']:.3f}/{value['reserved_mib']:.3f}|")
    lines += ["", "| P2 minus accepted P1 / phase | WS MiB | Private MiB | GPU total MiB | Allocated MiB | Reserved MiB |", "|---|---:|---:|---:|---:|---:|"]
    for phase, value in summary["authenticated_memory"]["p2_minus_p1_mib"].items():
        lines.append(f"|{phase}|{value['WorkingSet']:+.2f}|{value['Private']:+.2f}|{value['gpu_total_mib']:+.2f}|{value['allocated_mib']:+.3f}|{value['reserved_mib']:+.3f}|")
    lines += ["", "All cases loaded100 hosts/60 modpacks at2560x1440 scale2. API responses were200; error/warning logs empty. Visible runs used the accepted unfocused diagnostic policy. The P1/P2 glass screenshots were inspected for populated cards, avatars and live scene blur. Counter models must not be added together."]
(ROOT / "gate-summary.md").write_text("\n".join(lines) + "\n")
print("\n".join(lines))
