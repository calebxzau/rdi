"""Usage: u1-platform-compare.py WINDOWS_CAPTURE_DIR PLATFORM_ARTIFACT_DIR."""
from pathlib import Path
import json
import sys
import numpy as np
from PIL import Image
results = {}
for time in ("0.0", "15.0", "45.0"):
    name = f"p0-rust-{time}.png"
    a, b = [np.asarray(Image.open(Path(path) / name).convert("RGB")).astype(int) for path in sys.argv[1:3]]
    if a.shape != b.shape:
        raise ValueError(f"{name}: size mismatch")
    d = np.abs(a - b)
    results[name] = {"rgb_mae_per_255": float(d.mean()), "max": int(d.max())}
print(json.dumps(results, indent=2))
