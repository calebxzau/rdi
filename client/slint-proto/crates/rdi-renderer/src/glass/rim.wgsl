// Copyright 2025, Christopher Banes and the Haze project contributors
// SPDX-License-Identifier: Apache-2.0
// Specular rim and edge shadow drawn over haze.wgsl, ported from Haze 2.0.1 GlassShaders.kt.
// Field order must match `RimParams` in mod.rs.

struct Params {
    corner_radii: vec4f,
    edge_shadow: vec4f,
    material_origin: vec2f,
    material_size: vec2f,
    light_position: vec2f,
    sample_step: f32,
    specular_intensity: f32,
    specular_exponent: f32,
    edge_softness: f32,
    opacity: f32,
}

@group(0) @binding(0) var<uniform> params: Params;

fn clamp_material(local: vec2f) -> vec2f {
    return clamp(local, vec2f(0.0), params.material_size);
}

fn material_sdf(local: vec2f) -> f32 {
    return sd_rounded_corners(local, params.material_size, params.corner_radii);
}

fn sdf_gradient(local: vec2f) -> vec2f {
    let h = params.sample_step;
    let left = material_sdf(clamp_material(local - vec2f(h, 0.0)));
    let right = material_sdf(clamp_material(local + vec2f(h, 0.0)));
    let up = material_sdf(clamp_material(local - vec2f(0.0, h)));
    let down = material_sdf(clamp_material(local + vec2f(0.0, h)));
    return vec2f(right - left, down - up) * (0.5 / max(h, 0.0001));
}

fn shade(coord: vec2f) -> vec4f {
    let local = coord - params.material_origin;
    let sd = material_sdf(local);
    let coverage = shape_coverage(sd, params.sample_step * 0.5);
    if (coverage <= 0.0) {
        return vec4f(0.0);
    }
    let edge_width = max(params.edge_softness, params.sample_step);
    let edge = 1.0 - smootherstep(clamp(-sd / max(edge_width, 0.0001), 0.0, 1.0));
    if (edge <= 0.0) {
        return vec4f(0.0);
    }
    let gradient = sdf_gradient(local);
    let normal = normalize(vec3f(-gradient.x, -gradient.y, 1.0));
    let light_direction_2d = safe_normalize(params.light_position - local, vec2f(0.0, -1.0));
    let light_direction = normalize(vec3f(light_direction_2d, 1.0));
    let specular_base = max(dot(normal, light_direction), 0.0);
    var specular = 1.0;
    if (params.specular_exponent != 0.0) {
        specular = pow(specular_base, params.specular_exponent);
    }
    let specular_alpha = specular * params.specular_intensity * edge;
    let shadow_alpha = params.edge_shadow.a * edge;
    let alpha = specular_alpha + shadow_alpha * (1.0 - specular_alpha);
    let color = vec3f(specular_alpha) + params.edge_shadow.rgb * shadow_alpha * (1.0 - specular_alpha);
    if (alpha <= 0.0) {
        return vec4f(0.0);
    }
    return vec4f(color, alpha) * coverage;
}

@fragment
fn fs(@builtin(position) pos: vec4f) -> @location(0) vec4f {
    return shade(pos.xy) * params.opacity;
}
