"""Compare M1 captures, repeated-capture noise, startup and clean-close evidence."""
from pathlib import Path
import json
import re
import statistics
import numpy as np
from PIL import Image

root = Path(__file__).resolve().parents[1] / "evidence/m1"

def pixels(a, b):
    a, b = np.asarray(Image.open(a)).astype(int), np.asarray(Image.open(b)).astype(int)
    if a.shape != b.shape:
        raise ValueError("capture dimensions differ")
    d = np.abs(a - b)
    return dict(mae=float(d.mean()), maximum=int(d.max()), changed=int((np.any(d, axis=2) if d.ndim == 3 else d != 0).sum()))

report = {"noise": {}, "comparison": {}, "startup": {}}
for compiler in ("fxc", "dxc"):
    for scale in ("1", "1.25", "2"):
        name = f"glass-{compiler}-{scale}"
        report["noise"][name] = pixels(root / f"before/{name}-1.png", root / f"before/{name}-2.png")
        for repeat in (1, 2):
            report["comparison"][f"{name}-{repeat}"] = pixels(
                root / f"before/{name}-{repeat}.png", root / f"after/{name}-{repeat}.png")
    for filename in sorted((root / f"before/scene-{compiler}-1").glob("*.png")):
        name = f"scene-{compiler}/{filename.name}"
        report["noise"][name] = pixels(filename, root / f"before/scene-{compiler}-2/{filename.name}")
        for repeat in (1, 2):
            report["comparison"][f"{name}-{repeat}"] = pixels(
                root / f"before/scene-{compiler}-{repeat}/{filename.name}",
                root / f"after/scene-{compiler}-{repeat}/{filename.name}")

strip = re.compile(r"\x1b\[[0-9;]*m")
for phase in ("before", "after"):
    stages = {}
    timing = {"First frame": [], "First live scene frame": []}
    memory = []
    closes = []
    for round in range(2, 7):
        log = strip.sub("", (root / f"runtime/{phase}-{round}.log").read_text(encoding="utf-8-sig"))
        for line in log.splitlines():
            if "Startup" in line or "first" in line.lower():
                stages.setdefault(str(round), []).append(line)
        for name in timing:
            match = re.search(re.escape(name) + r".*?elapsed_ms=(\d+)", log)
            if match:
                timing[name].append(int(match[1]))
        memory.append(json.loads((root / f"runtime/{phase}-{round}-memory.json").read_text(encoding="utf-8-sig")))
        closes.append((root / f"runtime/{phase}-{round}-close.txt").read_text(encoding="utf-8-sig").strip())
    report["startup"][phase] = dict(stages=stages, closes=closes, medians={k: statistics.median(v) for k,v in timing.items() if v}, memory=memory)
(root / "comparison.json").write_text(json.dumps(report, indent=2))
print("Comparisons:", len(report["comparison"]))
print("Worst:", max(report["comparison"].values(), key=lambda v: v["mae"]))
print("Noise:", max(report["noise"].values(), key=lambda v: v["mae"]))
