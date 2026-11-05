#!/usr/bin/env python3
"""Pose and pixel comparison for the P4b gate. Writes summary.json and side-by-side images."""
import json, sys
from pathlib import Path
import numpy as np
from PIL import Image
root = Path('evidence/bg-port/p4b')
TIMES = ['0.0', '15.0', '45.0']
def rgb(path):
    return np.asarray(Image.open(path).convert('RGB'), dtype=np.int16)
def metrics(a, b):
    d = np.abs(a - b)
    return {'mae': round(float(d.mean()), 4), 'max': int(d.max()), 'changed_pixels': int((d.max(axis=2) > 0).sum())}
result = {'poses': {}, 'frames': {}, 'crowd_region': {}, 'p4a_vs_p5': {}, 'crowd_vs_p4a': {}}
for variant in ['steve', 'mixed']:
    worst = dict.fromkeys(['x', 'y', 'z', 'yaw', 'phase', 'shadowX', 'shadowY', 'shadowZ'], 0.0)
    for t in TIMES:
        rust = json.load(open(root / f'preview/{variant}/crowd-poses-{t}.json'))['runners']
        kotlin = json.load(open(root / f'reference/{variant}/crowd-poses-{t}.json'))['runners']
        assert [r['id'] for r in rust] == [k['id'] for k in kotlin]
        assert [r['slot'] for r in rust] == [k['slot'] for k in kotlin]
        assert [r['visible'] for r in rust] == [k['visible'] for k in kotlin]
        for r, k in zip(rust, kotlin):
            if r['visible']:
                for key in worst:
                    worst[key] = max(worst[key], abs(r[key] - k[key]))
    result['poses'][variant] = worst
    for t in TIMES:
        k = rgb(root / f'reference/{variant}/kotlin-matched-{t}.png')
        r = rgb(root / f'preview/{variant}/p0-rust-{t}.png')
        result['frames'][f'{variant}-{t}'] = metrics(k, r)
        base = rgb(root / f'preview/p4a-only/p0-rust-{t}.png')
        mask = np.abs(r - base).max(axis=2) > 0
        ys, xs = np.nonzero(mask)
        result['crowd_vs_p4a'][f'{variant}-{t}'] = {'changed_pixels': int(mask.sum())}
        if mask.any():
            pad = 4
            y0, y1, x0, x1 = max(ys.min() - pad, 0), min(ys.max() + pad, r.shape[0]), max(xs.min() - pad, 0), min(xs.max() + pad, r.shape[1])
            kc, rc = k[y0:y1, x0:x1], r[y0:y1, x0:x1]
            region = metrics(kc, rc)
            region.update(box=[int(x0), int(y0), int(x1), int(y1)], actor_pixel_mae=round(float(np.abs(k - r)[mask].mean()), 4), median_actor_pixel_error=float(np.median(np.abs(k - r)[mask])))
            result['crowd_region'][f'{variant}-{t}'] = region
            compare = np.concatenate([kc, np.full((kc.shape[0], 6, 3), 255, dtype=np.int16), rc], axis=1).astype(np.uint8)
            image = Image.fromarray(compare)
            image.resize((image.width * 2, image.height * 2), Image.NEAREST).save(root / f'p4b-{variant}-crop-{t}.png')
        full = np.concatenate([k, r], axis=0).astype(np.uint8)
        Image.fromarray(full).save(root / f'p4b-{variant}-compare-{t}.png')
for t in TIMES:
    result['p4a_vs_p5'][t] = metrics(rgb(root / f'preview/p4a-only/p0-rust-{t}.png'), rgb(root / f'preview/p5-control/p0-rust-{t}.png'))
for name in ['steve', 'no-crowd']:
    path = root / f'preview/{name}/timing.json'
    if path.exists():
        timing = json.load(open(path))
        result.setdefault('timing', {})[name] = {k: timing[k] for k in ['mean_ms', 'median_ms', 'p95_ms', 'max_ms', 'cadence_fps']}
json.dump(result, open(root / 'compare-summary.json', 'w'), indent=1)
print(json.dumps(result, indent=1))
# Actor masks: pixels a crowd changes by a visible amount, measured on each renderer's own no-crowd frame.
THRESHOLD = 12
masks = {}
for t in TIMES:
    k_base = rgb(Path('evidence/bg-port/p5/reference/steve') / f'kotlin-matched-{t}.png')
    r_base = rgb(root / f'preview/p4a-only/p0-rust-{t}.png')
    k = rgb(root / f'reference/steve/kotlin-matched-{t}.png')
    r = rgb(root / f'preview/steve/p0-rust-{t}.png')
    km = np.abs(k - k_base).max(axis=2) >= THRESHOLD
    rm = np.abs(r - r_base).max(axis=2) >= THRESHOLD
    both = km & rm
    union = km | rm
    error = np.abs(k - r)[both]
    masks[t] = {'kotlin_actor_pixels': int(km.sum()), 'rust_actor_pixels': int(rm.sum()), 'iou': round(float(both.sum() / union.sum()), 4),
                'mae_in_both': round(float(error.mean()), 3), 'median_in_both': float(np.median(error)), 'mean_signed_rust_minus_kotlin': round(float((r - k)[both].mean()), 3)}
    ys, xs = np.nonzero(union)
    pad = 6
    y0, y1, x0, x1 = max(ys.min() - pad, 0), min(ys.max() + pad, 720), max(xs.min() - pad, 0), min(xs.max() + pad, 1280)
    kc, rc = k[y0:y1, x0:x1], r[y0:y1, x0:x1]
    out = np.concatenate([kc, np.full((6, kc.shape[1], 3), 255, dtype=np.int16), rc], axis=0).astype(np.uint8)
    Image.fromarray(out).save(root / f'p4b-actors-{t}.png')
    masks[t]['box'] = [int(x0), int(y0), int(x1), int(y1)]
result['actor_masks'] = masks
json.dump(result, open(root / 'compare-summary.json', 'w'), indent=1)
print(json.dumps(masks, indent=1))
