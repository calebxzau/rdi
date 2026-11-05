"""Reproduce the P1 visual attribution from GPU readbacks; requires numpy and Pillow."""
import json
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent / "evidence/bg-port/p1/visual"
LUMA = np.array([0.299, 0.587, 0.114])
HILL = (400, 410, 880, 720)
TUFT = (560, 425, 740, 475)
REGIONS = {"hill": HILL, "summit": (550, 448, 710, 465),
           "terrace": (550, 607, 670, 625), "sky": (20, 20, 100, 100)}


def rgb(path):
    return np.asarray(Image.open(ROOT / path).convert("RGB"), dtype=float)


def region(array, box):
    x, y, right, bottom = box
    return array[y:bottom, x:right]


def stats(array):
    mean = array.reshape(-1, 3).mean(0)
    return {"rgb": mean.tolist(), "luma": float(mean @ LUMA)}


def kotlin(probe):
    return np.fromfile(ROOT / f"reference/kotlin-probe{probe}.rgb32f", "<f4").reshape(720, 1280, 3)


def rust(probe):
    return np.fromfile(ROOT / f"final-probe{probe}/hdr-0.0.rgba16f", "<f2").reshape(720, 1280, 4).astype(float)


def comparison(filename, panels, box, zoom=1):
    width, height = box[2] - box[0], box[3] - box[1]
    result = Image.new("RGB", (width * zoom * len(panels), height * zoom + 40), "#181b20")
    draw = ImageDraw.Draw(result)
    for i, (label, path) in enumerate(panels):
        draw.text((i * width * zoom + 8, 6), label, fill="white")
        draw.text((i * width * zoom + 8, 21), f"1280x720 pixel region {box}", fill="#cccccc")
        image = Image.open(ROOT / path).convert("RGB").crop(box)
        result.paste(image.resize((width * zoom, height * zoom), Image.Resampling.NEAREST), (i * width * zoom, 40))
    result.save(ROOT / filename)


images = {
    "kotlin_full": "reference/kotlin-full-full-post.png",
    "kotlin_no_bloom": "reference/kotlin-full-no-bloom.png",
    "kotlin_no_fog_no_bloom": "reference/kotlin-full-no-fog-no-bloom.png",
    "kotlin_p1": "reference/kotlin-p1-no-bloom.png",
    "kotlin_p1_aa1": "reference/kotlin-p1-aa1-no-bloom.png",
    "before": "before/p0-rust-0.0.png",
    "albedo_only": "albedo-only/p0-rust-0.0.png",
    "final": "final/p0-rust-0.0.png",
}
summary = {"coordinates": "top-left; rectangles exclude right and bottom", "regions": REGIONS}
summary["display"] = {
    name: {label: stats(region(rgb(path), box)) for label, path in images.items()}
    for name, box in REGIONS.items()
}
hill = summary["display"]["hill"]
gap = hill["kotlin_full"]["luma"] - hill["before"]["luma"]
parts = {
    "P1 grayscale decoder": hill["albedo_only"]["luma"] - hill["before"]["luma"],
    "P1 sampling and shadow cutout": hill["final"]["luma"] - hill["albedo_only"]["luma"],
    "remaining matched P1": hill["kotlin_p1_aa1"]["luma"] - hill["final"]["luma"],
    "accepted MSAA difference": hill["kotlin_p1"]["luma"] - hill["kotlin_p1_aa1"]["luma"],
    "P2 water in background": hill["kotlin_no_fog_no_bloom"]["luma"] - hill["kotlin_p1"]["luma"],
    "P3 fog": hill["kotlin_no_bloom"]["luma"] - hill["kotlin_no_fog_no_bloom"]["luma"],
    "P3 bloom": hill["kotlin_full"]["luma"] - hill["kotlin_no_bloom"]["luma"],
}
summary["ordered_attribution"] = {
    "observed_gap_luma": gap,
    "components": {name: {"luma": value, "percent_of_observed_gap": value / gap * 100} for name, value in parts.items()},
    "closure_error": sum(parts.values()) - gap,
}
summary["post_ablation"] = {}
for variant in ["no-bloom", "no-saturation", "no-dark-desat", "unit-saturation", "unit-vibrance", "neutral"]:
    control = stats(region(rgb(f"reference/kotlin-full-{variant}.png"), HILL))
    summary["post_ablation"][variant] = {
        **control, "full_minus_control_luma": hill["kotlin_full"]["luma"] - control["luma"],
        "full_minus_control_percent": (hill["kotlin_full"]["luma"] / control["luma"] - 1) * 100,
    }

k, r = {i: kotlin(i) for i in [2, 3, 5]}, {i: rust(i) for i in [2, 3, 5]}
mask = (k[5][..., 0] < .5) & (r[5][..., 0] < .5)
mask &= (abs(k[3][..., 1] - .342) < .005) & (abs(r[3][..., 1] - .342) < .005)
mask &= (k[3][..., 2] > .99) & (r[3][..., 2] > .99)
mask &= (k[2][..., 1] / np.maximum(k[2][..., 0], .00001) > 1.6)
mask &= (r[2][..., 1] / np.maximum(r[2][..., 0], .00001) > 1.6)
inside = np.zeros((720, 1280), bool)
x, y, right, bottom = HILL
inside[y:bottom, x:right] = True
mask &= inside
safe = np.ones((718, 1278), bool)
for y in range(3):
    for x in range(3):
        safe &= mask[y:y + 718, x:x + 1278]
summary["grass_texture_noise"] = {"common_sunlit_grass_interior_pixels": int(safe.sum()), "images": {}}
for label in ["kotlin_p1", "kotlin_p1_aa1", "before", "albedo_only", "final"]:
    value = rgb(images[label]) @ LUMA
    lap = value[:-2, 1:-1] + value[2:, 1:-1] + value[1:-1, :-2] + value[1:-1, 2:] - 4 * value[1:-1, 1:-1]
    mean = value[1:-1, 1:-1][safe].mean()
    rms = np.sqrt(np.mean(lap[safe] ** 2))
    summary["grass_texture_noise"]["images"][label] = {"mean_luma": float(mean), "laplacian_rms": float(rms), "rms_over_mean": float(rms / mean)}

summary["tufts"] = {}
for name, box in [("summit", TUFT), ("hill", HILL)]:
    km, rm = region(k[5], box)[..., 0] > .5, region(r[5], box)[..., 0] > .5
    common = km & rm
    summary["tufts"][name] = {
        "box": box, "kotlin_pixels": int(km.sum()), "rust_pixels": int(rm.sum()),
        "common_pixels": int(common.sum()), "symmetric_difference_pixels": int((km ^ rm).sum()),
        "translucency_kotlin": float(region(k[5], box)[..., 1][common].mean()),
        "translucency_rust": float(region(r[5], box)[..., 1][common].mean()),
        "filtered_alpha_kotlin": float(region(k[5], box)[..., 2][common].mean()),
        "filtered_alpha_rust": float(region(r[5], box)[..., 2][common].mean()),
        "images": {label: stats(region(rgb(path), box)[common]) for label, path in images.items()},
    }

khdr = np.fromfile(ROOT / "reference/kotlin-p1-aa1-raw.rgb32f", "<f4").reshape(720, 1280, 3)
def rhdr(directory):
    return np.fromfile(ROOT / directory / "hdr-0.0.rgba16f", "<f2").reshape(720, 1280, 4).astype(float)[..., :3]
before_hdr, after_hdr = rhdr("before"), rhdr("final")
summary["hdr_samples"] = [{"xy": [x, y], "kotlin": khdr[y, x].tolist(), "before": before_hdr[y, x].tolist(), "final": after_hdr[y, x].tolist(),
                            "gradient_uv_kotlin": kotlin(4)[y, x, :2].tolist(), "gradient_uv_rust": rust(4)[y, x, :2].tolist()}
                           for x, y in [(650, 455), (600, 617), (640, 617), (500, 565), (600, 674)]]

# Cross-check the tiny mip chains against GL readback. This is an independent
# numpy reconstruction of textures.rs, not another renderer or a runtime upload.
summary["mip_comparison"] = {}
for material, filename in [("GRASS_TOP", "grass_block_top"), ("STONE", "stone"), ("SHORT_GRASS", "short_grass")]:
    source = Image.open(ROOT.parents[4] / "bg-renderer/src/main/resources/assets/minecraft/textures/block" / f"{filename}.png")
    array = np.asarray(source.convert("RGBA")).copy()
    def encode(value):
        return np.where(value <= .0031308, value * 12.92, 1.055 * np.maximum(value, 0) ** (1 / 2.4) - .055)
    if source.mode == "L":
        array[..., :3] = np.floor(encode(array[..., :3] / 255) * 255 + .5)
    levels = []
    for level in range(5):
        reference = np.fromfile(ROOT / f"reference/{material}-mip{level}.rgba", np.uint8).reshape(array.shape)
        levels.append({"level": level, "kotlin_mean_rgba": reference.mean((0, 1)).tolist(), "rust_reconstructed_mean_rgba": array.mean((0, 1)).tolist(),
                       "maximum_byte_error": int(np.max(abs(array.astype(int) - reference.astype(int))))})
        if array.shape[0] == 1:
            break
        height, width = array.shape[:2]
        values = array[..., :3] / 255
        linear = np.where(values <= .04045, values / 12.92, ((values + .055) / 1.055) ** 2.4)
        averaged = linear.reshape(height // 2, 2, width // 2, 2, 3).mean((1, 3))
        alpha = array[..., 3].reshape(height // 2, 2, width // 2, 2).mean((1, 3))
        array = np.concatenate([np.floor(encode(averaged) * 255 + .5).astype(np.uint8), np.floor(alpha[..., None] + .5).astype(np.uint8)], 2)
    summary["mip_comparison"][material] = levels

panels = [("Kotlin full reference", images["kotlin_full"]), ("Rust P1 before", images["before"]), ("Rust P1 fixed", images["final"])]
comparison("near-hill-before-after.png", panels, HILL)
comparison("near-hill-matched-post.png", [("Kotlin: no bloom/fog, AA off", images["kotlin_p1_aa1"]), *panels[1:]], HILL)
comparison("grass-detail-before-after.png", [("Kotlin: no bloom/fog, AA off", images["kotlin_p1_aa1"]), *panels[1:]], TUFT, 3)
for time in ["0.0", "15.0", "45.0"]:
    comparison(f"p1-fixed-compare-{time}.png", [("Kotlin full valley", f"../full/reference/kotlin-{time}.png"), ("Rust P1 fixed", f"final/p0-rust-{time}.png")], (0, 0, 1280, 720))

(ROOT / "visual-summary.json").write_text(json.dumps(summary, indent=2) + "\n")
print(json.dumps({"hill": hill, "attribution": summary["ordered_attribution"], "post": summary["post_ablation"], "noise": summary["grass_texture_noise"], "tufts": summary["tufts"]}, indent=2))
