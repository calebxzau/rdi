"""P3 matched stage captures and controlled authenticated memory evidence."""
import hashlib
import json
import re
from datetime import datetime
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent / "evidence/bg-port/p3"
ANSI = re.compile(r"\x1b\[[0-9;]*m")
REGIONS = {
    "sky_clouds": (200, 10, 1000, 120),
    "lake": (700, 395, 900, 425),
    "left_lake_mist": (360, 360, 700, 445),
    "valley": (180, 345, 1100, 480),
    "near_hill": (400, 410, 880, 720),
}

def rgb(path):
    return np.asarray(Image.open(path).convert("RGB"), dtype=float)


def pair(name, left, right, box=(0, 0, 1280, 720), zoom=1, matched=False):
    width, height = box[2] - box[0], box[3] - box[1]
    image = Image.new("RGB", (width * zoom * 2, height * zoom + 40), "#181b20")
    draw = ImageDraw.Draw(image)
    for i, (label, path) in enumerate([("Kotlin full post: no player, AA off" if matched else "Kotlin original reference", left), ("Rust P3: accepted AA/packing/sampling", right)]):
        draw.text((i * width * zoom + 8, 6), label, fill="white")
        draw.text((i * width * zoom + 8, 21), f"1280x720 region {box}", fill="#cccccc")
        crop = Image.open(path).convert("RGB").crop(box)
        image.paste(crop.resize((width * zoom, height * zoom), Image.Resampling.NEAREST), (i * width * zoom, 40))
    image.save(ROOT / name)


summary = {"times": {}, "regions": REGIONS}
noise = (ROOT / "reference/fog-noise.r8").read_bytes()
assert noise == (ROOT / "final/fog-noise.r8").read_bytes(), "Fog noise differs from Kotlin"
summary["noise"] = {"byte_exact": True, "bytes": len(noise), "sha256": hashlib.sha256(noise).hexdigest()}
for time in ["0.0", "15.0", "45.0"]:
    ref_path, rust_path = ROOT / f"reference/kotlin-matched-{time}.png", ROOT / f"final/p0-rust-{time}.png"
    reference, rendered = rgb(ref_path), rgb(rust_path)
    data = {"regions": {}, "full_image_mae": float(np.abs(reference-rendered).mean())}
    for name, (x, y, right, bottom) in REGIONS.items():
        a, b = reference[y:bottom, x:right], rendered[y:bottom, x:right]
        old = rgb(ROOT / f"../p2/final/p0-rust-{time}.png")[y:bottom, x:right]
        data["regions"][name] = {"kotlin_rgb": a.mean((0, 1)).tolist(), "rust_rgb": b.mean((0, 1)).tolist(), "mean_absolute_rgb_error": float(np.abs(a - b).mean()), "p2_against_full_post_mae": float(np.abs(a-old).mean())}
    for stage, width, height, channels in [("fog", 640, 360, 4), ("linear", 1280, 720, 3), ("hdr", 1280, 720, 3), ("bloom", 1280, 720, 4)]:
        a = np.fromfile(ROOT / f"reference/{stage}-{time}.{'rgba32f' if channels==4 else 'rgb32f'}", "<f4").reshape(height, width, channels)
        b = np.fromfile(ROOT / f"final/{stage}-{time}.{'rgba8' if stage=='bloom' else 'rgba16f'}", "u1" if stage=="bloom" else "<f2").reshape(height, width, 4).astype(float)[..., :channels]
        if stage == "bloom": b /= 255.
        assert np.isfinite(b).all(), (stage, time)
        entry = {"finite": True, "mean_absolute_error": float(np.abs(a-b).mean()), "p95_absolute_error": float(np.quantile(np.abs(a-b), .95))}
        if stage == "fog":
            mask = (a[..., 3] < .999) | (b[..., 3] < .999)
            entry.update(active_pixels=int(mask.sum()), active_mae=float(np.abs(a-b)[mask].mean()), transmittance_mae=float(np.abs(a[...,3]-b[...,3])[mask].mean()), alpha_min=float(b[...,3].min()), alpha_max=float(b[...,3].max()))
        data[stage] = entry
    old = rgb(ROOT / f"../p2/final/p0-rust-{time}.png")
    new = rgb(ROOT / f"p2-preserved/p0-rust-{time}.png")
    error = np.abs(old-new)
    data["p2_regression"] = {"maximum_channel_error": float(error.max()), "pixels_over_one": int((error.max(2)>1).sum())}
    assert data["p2_regression"]["maximum_channel_error"] <= 1
    pair(f"p3-compare-{time}.png", ROOT / f"reference/kotlin-original-{time}.png", rust_path)
    pair(f"p3-matched-{time}.png", ref_path, rust_path, matched=True)
    pair(f"p3-valley-crop-{time}.png", ref_path, rust_path, (280, 340, 1100, 480), 2, True)
    summary["times"][time] = data
pair("p3-sky-crop-0.0.png", ROOT / "reference/kotlin-matched-0.0.png", ROOT / "final/p0-rust-0.0.png", (180, 0, 1020, 170), 1, True)
summary["preview_timing"] = json.loads((ROOT / "final/timing.json").read_text())
log = ANSI.sub("", (ROOT / "final.log").read_text())
summary["new_pipeline_wall_ms"] = dict((name,float(ms)) for name,ms in re.findall(r'pipeline="(scene (?:valley fog march|bilateral fog HDR copy|bloom atlas|bloom tonemap saturation|linear HDR downsample))".*?compile_ms=([0-9.]+)', log))
summary["logical_payload_delta_mib"] = (64**3 + 640*360*8 + sum(max(1,1280>>l)*max(1,720>>l)*8 for l in range(9)) + 1280*720*4 + 32) / 2**20
summary["authenticated_memory"] = {"status": "pending"}
if (ROOT / "measure/complete.exit").exists():
    assert (ROOT / "measure/complete.exit").read_text().strip() == "0"
    memory = {}
    for case in ["static", "p2", "p3"]:
        assert (ROOT / f"measure/{case}-measure.exit").read_text().strip() == "0"
        log = ANSI.sub("", (ROOT / f"measure/{case}-app.log").read_text())
        assert "Menu data loaded" in log and "Menu login/load failed" not in log
        assert not re.search(r"\b(?:WARN|ERROR)\b|Scene unavailable|Scene loader failed", log), case
        rows = [json.loads(line) for line in (ROOT / f"measure/{case}-memory.jsonl").read_text().splitlines()]
        snapshots = []
        for line in log.splitlines():
            if "Memory allocator snapshot" in line:
                snapshot = json.loads(line.split("data=", 1)[1])
                snapshot["time"] = datetime.fromisoformat(line.split()[0].replace("Z", "+00:00"))
                snapshots.append(snapshot)
        endpoints = {}
        for phase in ["loaded", "foreground60s", "minimized30s", "restored10s"]:
            values = [v for v in rows if v["Phase"] == phase]
            assert values, (case, phase)
            value = values[0] if phase == "loaded" else values[-1]
            assert value["Authenticated"]
            cutoff = datetime.fromisoformat(value["Time"].replace("Z", "+00:00"))
            prior = [s for s in snapshots if s["time"] <= cutoff and s.get("report")]
            allocator = prior[-1]["report"] if prior else None
            endpoints[phase] = {**value, **{k: value[k] / 2**20 for k in ["WorkingSet", "Private", "Dedicated", "Shared"]},
                "gpu_total_mib": (value["Dedicated"]+value["Shared"])/2**20,
                "allocated_mib": allocator["allocated"]/2**20 if allocator else None,
                "reserved_mib": allocator["reserved"]/2**20 if allocator else None,
                "blocks_mib": [b["size"]/2**20 for b in allocator["blocks"]] if allocator else None}
        statuses = re.findall(r'API response.*?path="([^"]+)".*?status=(\d+)', log)
        counts = re.search(r'Menu data loaded.*?hosts=(\d+).*?modpacks=(\d+)', log)
        assert statuses and all(status=="200" for _,status in statuses), case
        assert counts, case
        assert endpoints["foreground60s"]["Width"]==2560 and endpoints["foreground60s"]["Height"]==1440
        assert endpoints["minimized30s"]["ActualMinimized"] and not endpoints["restored10s"]["ActualMinimized"]
        completion = [(float(a),float(b)) for a,b in re.findall(r'Scene completion window.*?average_ms=([0-9.]+).*?max_ms=([0-9.]+)',log)]
        startup = dict((name,int(ms)) for name,ms in re.findall(r'(First live scene frame|First frame).*?elapsed_ms=(\d+)', log))
        memory[case] = {"endpoints": endpoints, "hosts":int(counts[1]), "modpacks":int(counts[2]), "api_responses":len(statuses), "api_paths":sorted(set(p for p,_ in statuses)), "startup_ms":startup,
            "completion_windows":len(completion), "mean_window_ms":float(np.mean([a for a,b in completion])) if completion else None, "max_window_frame_ms":max((b for a,b in completion),default=None)}
    fields = ["WorkingSet", "Private", "gpu_total_mib", "allocated_mib", "reserved_mib"]
    delta = {}
    for phase in ["foreground60s", "minimized30s", "restored10s"]:
        delta[phase] = {k: memory["p3"]["endpoints"][phase][k] - memory["p2"]["endpoints"][phase][k] for k in fields}
        delta[phase]["ws_minus_allocated_delta_mib"] = delta[phase]["WorkingSet"]-delta[phase]["allocated_mib"]
    summary["authenticated_memory"] = {"status":"passed", "cases":memory, "p3_minus_accepted_p2":delta}
# An independent headless counter distinguishes retained Rust data from native process memory.
if (ROOT / "cpu/complete.exit").exists():
    headless = {}
    for case in ["p2-create", "p3-create", "p2-render", "p3-render"]:
        rows = [json.loads(line) for line in (ROOT / f"cpu/{case}-memory.jsonl").read_text().splitlines()]
        text = (ROOT / f"cpu/{case}.log").read_text()
        rust = {v["phase"]:v for v in [json.loads(line[7:]) for line in text.splitlines() if line.startswith("P3_CPU ")]}
        phases = {}
        for line in text.splitlines():
            if "Memory allocator snapshot" not in line: continue
            snapshot = json.loads(line.split("data=",1)[1])
            date = datetime.fromisoformat(line.split()[0].replace("Z", "+00:00"))
            candidates = [v for v in rows if 1 < (datetime.fromisoformat(v["Time"].replace("Z", "+00:00"))-date).total_seconds() < 2.8]
            if not candidates: continue
            row = candidates[-1]
            phases[snapshot["phase"]] = {**{k:row[k]/2**20 for k in ["WorkingSet","Private","Dedicated","Shared"]}, "allocated_mib":snapshot["report"]["allocated"]/2**20,"reserved_mib":snapshot["report"]["reserved"]/2**20, **rust[snapshot["phase"]]}
        headless[case] = phases
    summary["cpu_headless"] = headless
if (ROOT / "cpu-app/complete.exit").exists():
    app = {}
    for case in ["p2", "p3"]:
        rows = [json.loads(line) for line in (ROOT / f"cpu-app/{case}-memory.jsonl").read_text().splitlines()]
        text = ANSI.sub("", (ROOT / f"cpu-app/{case}-app.log").read_text())
        rust=[]
        alloc=[]
        for line in text.splitlines():
            date = datetime.fromisoformat(line.split()[0].replace("Z", "+00:00"))
            if "Rust allocation snapshot" in line:
                values = re.search(r'rust_live_bytes=(\d+).*?rust_peak_bytes=(\d+)',line)
                rust.append((date, int(values[1]), int(values[2])))
            if "Memory allocator snapshot" in line:
                values = json.loads(line.split("data=",1)[1])["report"]
                alloc.append((date,values))
        ends={}
        for phase in ["loaded","foreground60s","minimized30s","restored10s"]:
            matches=[v for v in rows if v["Phase"]==phase]
            row=matches[0] if phase=="loaded" else matches[-1]
            date=datetime.fromisoformat(row["Time"].replace("Z", "+00:00"))
            r=[v for v in rust if v[0]<=date][-1]
            a=[v for v in alloc if v[0]<=date][-1][1]
            ends[phase]={**{k:row[k]/2**20 for k in ["WorkingSet","Private","Dedicated","Shared"]},"rust_live_mib":r[1]/2**20,"rust_peak_mib":r[2]/2**20,"allocated_mib":a["allocated"]/2**20,"reserved_mib":a["reserved"]/2**20}
        app[case]=ends
    app["p3_minus_p2"]={phase:{k:app["p3"][phase][k]-app["p2"][phase][k] for k in app["p2"][phase]} for phase in app["p2"]}
    summary["cpu_app"]=app
(ROOT / "summary.json").write_text(json.dumps(summary, indent=2)+"\n")
print(json.dumps({"noise":summary["noise"], "preview_timing":{k:v for k,v in summary["preview_timing"].items() if k!="samples_ms"}, "new_pipeline_wall_ms":summary["new_pipeline_wall_ms"], "payload_mib":summary["logical_payload_delta_mib"], "memory_status":summary["authenticated_memory"]["status"], "images":summary["times"]}, indent=2))
