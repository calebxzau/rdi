"""Create local measurement JSON and a small markdown table from the focused U1 run."""
from pathlib import Path
import json
import re
import statistics
import numpy as np
from PIL import Image

root = Path(__file__).resolve().parents[1] / "evidence/ui-port/u1"
run = root / "focused-images"
text = re.sub(r"\x1b\[[0-9;]*m", "", (run / "run-app.log").read_text(encoding="utf-8-sig"))
measurements = [json.loads(line.split("data=", 1)[1]) for line in text.splitlines() if "U1 measurements data=" in line]
samples = [json.loads(line) for line in (run / "memory.jsonl").read_text(encoding="utf-8-sig").splitlines()]
beats = [json.loads(line.split("data=", 1)[1]) for line in text.splitlines() if "Runtime probe data=" in line]
result = {"runs": measurements, "all_samples_foreground": all(s["focused"] for s in samples),
          "frame_units": "wall clock milliseconds between AfterRendering callbacks; CPU excludes presentation",
          "warm_memory_mib": {}}
# Use the tail of each scroll interval, excluding initial upload/allocator warmup.
for round in (1, 2, 3):
    values = [s for s in samples if s["round"] == round and 20 <= s["second"] <= 28]
    result["warm_memory_mib"][round] = {key: statistics.median(s[key] for s in values)/1048576 for key in ("ws", "private")}
comparison = {}
for p in (root / "windows-final").glob("glass-*.png"):
    a = np.asarray(Image.open(p)).astype(int)
    old = root.parents[1] / "m1/before" / (p.stem + "-1.png")
    if old.exists():
        b = np.asarray(Image.open(old)).astype(int)
        comparison[p.name] = {"mae": float(np.abs(a-b).mean()), "max": int(np.abs(a-b).max())}
result["legacy_glass_comparison"] = comparison
(root / "measurements.json").write_text(json.dumps(result, indent=2))
for i, m in enumerate(measurements, 1):
    print(i, "valid", m["valid"], "images", m["network_images"], "list", m["list"], "panel", m["panel"])
print("Memory:", result["warm_memory_mib"])
print("Foreground samples:", result["all_samples_foreground"])
print("Glass:", comparison)
