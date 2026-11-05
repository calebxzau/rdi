"""Summarise the GU1 pacing rerun into measurements JSON and a table on stdout."""
from pathlib import Path
import json, re, statistics, sys
root = Path(__file__).resolve().parents[1] / "evidence/ui-port/u1" / (sys.argv[1] if len(sys.argv) > 1 else "pacing-60hz")
rows = []
for run in sorted((p for p in root.iterdir() if p.is_dir()), key=lambda p: int(p.name.split("-")[0])):
    case = run.name.split("-", 1)[1]
    text = re.sub(r"\x1b\[[0-9;]*m", "", (run / "run-app.log").read_text(encoding="utf-8-sig"))
    found = [json.loads(l.split("data=", 1)[1]) for l in text.splitlines() if "U1 measurements data=" in l]
    samples = [json.loads(l) for l in (run / "memory.jsonl").read_text(encoding="utf-8-sig").splitlines()] if (run / "memory.jsonl").exists() else []
    exit_code = (run / "exit.code").read_text(encoding="utf-8-sig").strip() if (run / "exit.code").exists() else None
    window = json.loads((run / "window.json").read_text(encoding="utf-8-sig")) if (run / "window.json").exists() else None
    warm = [s for s in samples if 20 <= s["second"] <= 28]
    rows.append(dict(run=run.name, case=case, m=found[0] if found else None, exit=exit_code, window=window,
        focused=all(s["focused"] for s in samples) if samples else None,
        warm_ws=statistics.median(s["ws"] for s in warm)/1048576 if warm else None,
        warm_private=statistics.median(s["private"] for s in warm)/1048576 if warm else None))
(root / "measurements.json").write_text(json.dumps(rows, indent=1), encoding="utf-8")
print("run|valid|exit|fg|list fps|long%|n|p50|p95|p99|worst|cpu p50|cpu p95|cpu p99|underlay p50|p95|WS|priv")
for r in rows:
    m = r["m"]
    if not m: print(r["run"], "NO DATA", r["exit"]); continue
    l, c, u, q = m["list"], m["list_cpu"], m["list_underlay"], m["list_pacing"]
    print(r["run"], m["valid"], r["exit"], r["focused"], f'{q["delivered_fps"]:.2f}', f'{q["long_frame_pct"]:.2f}', q["count"],
      *(f'{l[k]:.2f}' for k in ("p50_ms","p95_ms","p99_ms","worst_ms")), *(f'{c[k]:.2f}' for k in ("p50_ms","p95_ms","p99_ms")),
      *((f'{u[k]:.2f}' for k in ("p50_ms","p95_ms")) if u["count"] else ("-","-")), f'{r["warm_ws"]:.0f}', f'{r["warm_private"]:.0f}', sep="|")
print("--panel")
for r in rows:
    m = r["m"]
    if not m: continue
    if m["menu"]: print(r["run"], "scroll range", m["menu_scroll_range"]); continue
    l, c, q = m["panel"], m["panel_cpu"], m["panel_pacing"]
    print(r["run"], f'{q["delivered_fps"]:.2f}', f'{q["long_frame_pct"]:.2f}', q["count"], *(f'{l[k]:.2f}' for k in ("p50_ms","p95_ms","p99_ms","worst_ms")), f'{c["p95_ms"]:.2f}', sep="|")
print("window", rows[0]["window"])
