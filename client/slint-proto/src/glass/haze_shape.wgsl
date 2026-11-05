// Copyright 2025, Christopher Banes and the Haze project contributors
// SPDX-License-Identifier: Apache-2.0
// Shape helpers shared by haze.wgsl and rim.wgsl, ported from Haze 2.0.1 GlassShaders.kt.
// Local coordinates start at the material's top-left corner.

fn shape_coverage(sd: f32, pixel_width: f32) -> f32 {
    // Pixel coverage is independent of the authored optical edge softness and density.
    // Keep the transition inside the existing silhouette, including caller-applied clips.
    return clamp(-sd / max(pixel_width, 0.0001), 0.0, 1.0);
}

fn smootherstep(x: f32) -> f32 {
    let t = clamp(x, 0.0, 1.0);
    return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
}

fn sd_rectangle(local: vec2f, size: vec2f) -> f32 {
    let half_size = size * 0.5;
    let edge_distance = abs(local - half_size) - half_size;
    let outside = length(max(edge_distance, vec2f(0.0)));
    let inside = min(max(edge_distance.x, edge_distance.y), 0.0);
    return outside + inside;
}

// radii: top-left, top-right, bottom-right, bottom-left
fn sd_rounded_corners(local: vec2f, size: vec2f, radii: vec4f) -> f32 {
    var sd = sd_rectangle(local, size);
    if (local.x < radii.x && local.y < radii.x) {
        sd = max(sd, length(local - vec2f(radii.x)) - radii.x);
    }
    if (local.x > size.x - radii.y && local.y < radii.y) {
        sd = max(sd, length(local - vec2f(size.x - radii.y, radii.y)) - radii.y);
    }
    if (local.x > size.x - radii.z && local.y > size.y - radii.z) {
        sd = max(sd, length(local - vec2f(size.x - radii.z, size.y - radii.z)) - radii.z);
    }
    if (local.x < radii.w && local.y > size.y - radii.w) {
        sd = max(sd, length(local - vec2f(radii.w, size.y - radii.w)) - radii.w);
    }
    return sd;
}

fn safe_normalize(value: vec2f, fallback: vec2f) -> vec2f {
    let len = length(value);
    if (len > 0.0001) {
        return value / len;
    }
    return fallback;
}
