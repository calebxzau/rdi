"""Summarize focused release UI/service samples without mixing debug/other gates."""
import json
import re
import statistics
import sys
from pathlib import Path

path = Path(sys.argv[1])
runs = [r for r in json.loads((path / 'samples.json').read_text()) if not r['warmup']]
assert len(runs) == 3
assert all(s['osFocused'] and s['appFocused'] and s['active'] for r in runs for s in r['samples'])
keys = ['uiWSMiB', 'uiPrivateMiB', 'serviceWSMiB', 'servicePrivateMiB']
summary = {k: statistics.median(statistics.median(s[k] for s in r['samples']) for r in runs) for k in keys}
first_frames, scene_frames, handshakes = [], [], []
for run in runs:
    log = re.sub(r'\x1b\[[0-9;]*m', '', (path / f'run-{run["run"]}-app.log').read_text())
    first_frames.append(int(re.search(r'First frame elapsed_ms=(\d+)', log)[1]))
    scene_frames.append(int(re.search(r'First live scene frame elapsed_ms=(\d+)', log)[1]))
for i in range(1, 4):
    log = (path / f'service-{i}.log').read_text()
    assert 'EofExit=0' in log and 'EmptyLogin=pass' in log
    handshakes.append(int(re.search(r'HandshakeMs=(\d+)', log)[1]))
summary.update(firstFrameMs=statistics.median(first_frames), firstSceneMs=statistics.median(scene_frames),
               serviceHandshakeMs=statistics.median(handshakes),
               firstFrameSamples=first_frames, firstSceneSamples=scene_frames, serviceHandshakeSamples=handshakes,
               focusedSamples=sum(len(r['samples']) for r in runs), binary=json.loads((path/'binary.json').read_text()))
(path / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
print(json.dumps(summary, indent=2))
