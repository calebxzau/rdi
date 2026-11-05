// Copyright 2025, Christopher Banes and the Haze project contributors
// SPDX-License-Identifier: Apache-2.0
// Refracting glass body of Haze 2.0.1 GlassShaders.kt, used for the tab bar and bottom buttons.
// `content` is the pre-blurred background. Field order must match `HazeParams` in mod.rs.

struct Params {
    corner_radii: vec4f,
    tint_color: vec4f,
    content_transform: vec4f,
    sample_size: vec2f,
    material_origin: vec2f,
    material_size: vec2f,
    sample_step: f32,
    refraction_strength: f32,
    refraction_fold_strength: f32,
    ambient_response: f32,
    edge_softness: f32,
    refraction_height: f32,
    edge_refraction_width: f32,
    chromatic_aberration_strength: f32,
    // Floats because the original AGSL has no int uniforms.
    surface_profile: f32,
    chromatic_aberration_mode: f32,
    contrast: f32,
    white_point: f32,
    chroma_multiplier: f32,
    refraction_scale: f32,
    content_normal_blend: f32,
    fresnel_exponent: f32,
    opacity: f32,
}

@group(0) @binding(0) var<uniform> params: Params;

fn to_linear_srgb(c: vec3f) -> vec3f {
    return select(c / 12.92, pow((c + 0.055) / 1.055, vec3f(2.4)), c > vec3f(0.04045));
}

fn from_linear_srgb(c: vec3f) -> vec3f {
    return select(
        c * 12.92,
        1.055 * pow(max(c, vec3f(0.0)), vec3f(1.0 / 2.4)) - 0.055,
        c > vec3f(0.0031308),
    );
}

fn clamp_sample(coord: vec2f) -> vec2f {
    return clamp(coord, vec2f(0.5), params.sample_size - vec2f(0.5));
}

fn clamp_material(local: vec2f) -> vec2f {
    return clamp(local, vec2f(0.0), params.material_size);
}

fn reversed_smootherstep(x: vec4f) -> vec4f {
    let t = clamp(x, vec4f(0.0), vec4f(1.0));
    return vec4f(1.0) - t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
}

fn grad_sd_rectangle(local: vec2f, size: vec2f, blend_width: f32) -> vec2f {
    let edge_distance = vec4f(-local.x, local.x - size.x, -local.y, local.y - size.y);
    let max_distance = max(
        max(edge_distance.x, edge_distance.y),
        max(edge_distance.z, edge_distance.w),
    );
    let weights = reversed_smootherstep((vec4f(max_distance) - edge_distance) / blend_width);
    let total_weight = dot(weights, vec4f(1.0));
    return vec2f(weights.y - weights.x, weights.w - weights.z) / max(total_weight, 0.0001);
}

fn grad_sd_rounded_rect(local: vec2f, size: vec2f, radii: vec4f, blend_width: f32) -> vec2f {
    let rectangle_gradient = grad_sd_rectangle(local, size, blend_width);
    let top_left = min(local - vec2f(radii.x), vec2f(0.0));
    let top_right = vec2f(max(local.x - (size.x - radii.y), 0.0), min(local.y - radii.y, 0.0));
    let bottom_right = max(local - vec2f(size.x - radii.z, size.y - radii.z), vec2f(0.0));
    let bottom_left = vec2f(min(local.x - radii.w, 0.0), max(local.y - (size.y - radii.w), 0.0));
    let corner_weights = vec4f(
        smootherstep(clamp(length(top_left) / max(radii.x, 0.0001), 0.0, 1.0)),
        smootherstep(clamp(length(top_right) / max(radii.y, 0.0001), 0.0, 1.0)),
        smootherstep(clamp(length(bottom_right) / max(radii.z, 0.0001), 0.0, 1.0)),
        smootherstep(clamp(length(bottom_left) / max(radii.w, 0.0001), 0.0, 1.0)),
    );
    let corner_gradient = safe_normalize(
        safe_normalize(top_left, rectangle_gradient) * corner_weights.x
            + safe_normalize(top_right, rectangle_gradient) * corner_weights.y
            + safe_normalize(bottom_right, rectangle_gradient) * corner_weights.z
            + safe_normalize(bottom_left, rectangle_gradient) * corner_weights.w,
        rectangle_gradient,
    );
    let corner_weight = max(
        max(corner_weights.x, corner_weights.y),
        max(corner_weights.z, corner_weights.w),
    );
    return mix(rectangle_gradient, corner_gradient, corner_weight);
}

fn circle_map(x: f32) -> f32 {
    return 1.0 - sqrt(max(0.0, 1.0 - x * x));
}

fn squircle_map(t: f32) -> f32 {
    return 1.0 - smootherstep(t * sqrt(t));
}

fn evaluate_profile(t: f32) -> f32 {
    let x = 1.0 - clamp(t, 0.0, 1.0);
    if (params.surface_profile == 1.0) {
        return squircle_map(t);
    } else if (params.surface_profile == 2.0) {
        return -circle_map(x);
    } else if (params.surface_profile == 3.0) {
        let convex = circle_map(x);
        let concave = -circle_map(x);
        let blend = smootherstep(clamp(t / 0.7, 0.0, 1.0));
        return mix(convex, concave, blend);
    }
    return circle_map(x);
}

fn elongation_weight() -> f32 {
    let shortest_side = max(min(params.material_size.x, params.material_size.y), 0.0001);
    let aspect_ratio = max(params.material_size.x, params.material_size.y) / shortest_side;
    // Preserve near-square optics and complete the transition for long controls.
    return smootherstep(clamp((aspect_ratio - 1.5) / 1.5, 0.0, 1.0));
}

fn normalized_material_coord(local: vec2f) -> vec2f {
    let half_size = max(params.material_size * 0.5, vec2f(0.0001));
    return (local - half_size) / half_size;
}

fn optical_field_weight() -> f32 {
    let inradius = max(min(params.material_size.x, params.material_size.y) * 0.5, 0.0001);
    // Begin the transition before opposing edge profiles reach the medial axis.
    let overlap_weight = smootherstep(
        clamp((params.refraction_height / inradius - 0.75) / 0.25, 0.0, 1.0),
    );
    return elongation_weight() * overlap_weight;
}

fn dome_radius(normalized: vec2f) -> f32 {
    let squared = normalized * normalized;
    // A smooth rectangular radius: zero at center and one on every axis-aligned edge.
    return sqrt(clamp(squared.x + squared.y - squared.x * squared.y, 0.0, 1.0));
}

fn dome_distance(local: vec2f) -> f32 {
    return max((1.0 - dome_radius(normalized_material_coord(local))) * params.refraction_height, 0.0);
}

fn optical_distance_from_signed_distance(local: vec2f, sd: f32, field_weight: f32) -> f32 {
    let dist_to_edge = max(-sd, 0.0);
    if (field_weight <= 0.0) {
        return dist_to_edge;
    }
    let distance_from_dome = dome_distance(local);
    if (field_weight >= 1.0) {
        return distance_from_dome;
    }
    return mix(dist_to_edge, distance_from_dome, field_weight);
}

fn surface_height_from_optical_distance(optical_distance: f32) -> f32 {
    let refraction_zone = max(params.refraction_height, 0.0001);
    let t = clamp(optical_distance / refraction_zone, 0.0, 1.0);
    return evaluate_profile(t) * refraction_zone;
}

fn surface_height(local: vec2f, field_weight: f32) -> f32 {
    if (field_weight >= 1.0) {
        return surface_height_from_optical_distance(dome_distance(local));
    }
    let sd = sd_rounded_corners(local, params.material_size, params.corner_radii);
    let optical_distance = optical_distance_from_signed_distance(local, sd, field_weight);
    return surface_height_from_optical_distance(optical_distance);
}

fn surface_height_norm_from_optical_distance(optical_distance: f32) -> f32 {
    let refraction_zone = max(params.refraction_height, 0.0001);
    return clamp(surface_height_from_optical_distance(optical_distance) / refraction_zone, -1.0, 1.0);
}

fn optical_surface_gradient(local: vec2f, field_weight: f32) -> vec2f {
    let normalized = normalized_material_coord(local);
    let squared = normalized * normalized;
    let dome_gradient = vec2f(normalized.x * (1.0 - squared.y), normalized.y * (1.0 - squared.x));
    if (field_weight >= 1.0) {
        return dome_gradient;
    }
    let normal_blend_width = max(params.refraction_height, 1.0);
    let boundary_gradient = grad_sd_rounded_rect(
        local, params.material_size, params.corner_radii, normal_blend_width,
    );
    if (field_weight <= 0.0) {
        return boundary_gradient;
    }
    return mix(boundary_gradient, dome_gradient, field_weight);
}

fn refraction_fold_envelope(optical_distance: f32) -> f32 {
    let fold_width = max(
        select(params.refraction_height, params.edge_refraction_width, params.edge_refraction_width >= 0.0),
        params.sample_step,
    );
    let fold_t = clamp(optical_distance / fold_width, 0.0, 1.0);
    let fold_rise = smootherstep(clamp(fold_t / 0.2, 0.0, 1.0));
    let fold_fall = 1.0 - smootherstep(clamp((fold_t - 0.25) / 0.35, 0.0, 1.0));
    return fold_rise * fold_fall;
}

fn folded_refraction_height_norm(height_norm: f32, optical_distance: f32) -> f32 {
    if (params.refraction_fold_strength <= 0.0) {
        return height_norm;
    }
    let fold_envelope = refraction_fold_envelope(optical_distance);
    let fold_weight = clamp(params.refraction_fold_strength * fold_envelope, 0.0, 1.0);
    // Reverse the sampling derivative without reversing displacement through zero.
    let reverse = params.edge_refraction_width < 0.0 && params.surface_profile == 2.0;
    let fold_target = select(1.0, -1.0, reverse) * abs(height_norm) * 0.02;
    return mix(height_norm, fold_target, fold_weight);
}

fn edge_refraction_gradient(local: vec2f) -> vec2f {
    let size = params.material_size;
    // The cap keeps the optical corner regions disjoint, allowing one analytic arc normal.
    let radii = min(params.corner_radii * 1.5, vec4f(min(size.x, size.y) * 0.5));
    var delta = vec2f(0.0);
    var corner = false;
    if (local.x < radii.x && local.y < radii.x) {
        delta = local - vec2f(radii.x);
        corner = true;
    } else if (local.x > size.x - radii.y && local.y < radii.y) {
        delta = local - vec2f(size.x - radii.y, radii.y);
        corner = true;
    } else if (local.x > size.x - radii.z && local.y > size.y - radii.z) {
        delta = local - vec2f(size.x - radii.z, size.y - radii.z);
        corner = true;
    } else if (local.x < radii.w && local.y > size.y - radii.w) {
        delta = local - vec2f(radii.w, size.y - radii.w);
        corner = true;
    }
    let blend_width = max(params.sample_step * 0.5, 0.01);
    if (!corner) {
        return grad_sd_rectangle(local, size, blend_width);
    }
    let corner_distance = length(delta);
    let axis_distance = min(abs(delta.x), abs(delta.y));
    if (axis_distance >= blend_width) {
        return delta / corner_distance;
    }
    // Join the straight-edge field at both corner axes, including their undefined arc centre.
    // Away from this one-sample transition the normal is the exact analytic radial direction.
    let corner_weight = smootherstep(clamp(axis_distance / blend_width, 0.0, 1.0));
    return mix(
        grad_sd_rectangle(local, size, blend_width),
        delta / max(corner_distance, 0.0001),
        corner_weight,
    );
}

fn refraction_displacement(
    local: vec2f, height_norm: f32, optical_distance: f32, refraction_multiplier: f32,
    field_weight: f32,
) -> vec2f {
    let strength = clamp(params.refraction_strength * refraction_multiplier, 0.0, 1.0);
    let edge_width = params.edge_refraction_width;
    if (edge_width >= 0.0) {
        if (edge_width <= 0.0 || optical_distance >= edge_width) {
            return vec2f(0.0);
        }
        let t = clamp(optical_distance / edge_width, 0.0, 1.0);
        let cutoff = max(1.0 - t * t, 0.0);
        // Empirical Clear fit: rapid boundary decay with a smooth finite-width cutoff.
        let profile = exp(-t / 0.225) * cutoff * cutoff;
        let magnitude = folded_refraction_height_norm(profile, optical_distance) * min(
            strength * params.refraction_scale,
            min(params.material_size.x, params.material_size.y) * 0.5,
        );
        let gradient = edge_refraction_gradient(local);
        let gradient_length = length(gradient);
        let center_fade = smootherstep(clamp(gradient_length / 0.5, 0.0, 1.0));
        return -gradient / max(gradient_length, 0.0001) * center_fade * magnitude;
    }
    let effective_height_norm = folded_refraction_height_norm(height_norm, optical_distance);
    let displacement_magnitude = effective_height_norm * strength * params.refraction_scale;
    let optical_gradient = optical_surface_gradient(local, field_weight);
    var displacement_gradient = optical_gradient;
    if (field_weight < 1.0) {
        let gradient_length = length(optical_gradient);
        let center_fade = smootherstep(clamp(gradient_length / 0.5, 0.0, 1.0));
        let normalized_gradient = optical_gradient / max(gradient_length, 0.0001) * center_fade;
        displacement_gradient = mix(normalized_gradient, optical_gradient, field_weight);
    }
    return -displacement_gradient * displacement_magnitude;
}

fn sample_depth(coord: vec2f) -> vec4f {
    return sample_content(clamp_sample(coord), params.content_transform);
}

fn surface_gradient(local: vec2f, field_weight: f32) -> vec2f {
    let h = params.sample_step;
    let left = surface_height(clamp_material(local - vec2f(h, 0.0)), field_weight);
    let right = surface_height(clamp_material(local + vec2f(h, 0.0)), field_weight);
    let up = surface_height(clamp_material(local - vec2f(0.0, h)), field_weight);
    let down = surface_height(clamp_material(local + vec2f(0.0, h)), field_weight);
    return vec2f(right - left, down - up) * (0.5 / max(h, 0.0001));
}

fn surface_lighting_gradient(local: vec2f, sd: f32, field_weight: f32) -> vec2f {
    if (params.surface_profile == 1.0) {
        let refraction_zone = max(params.refraction_height, 0.0001);
        let optical_distance = optical_distance_from_signed_distance(local, sd, field_weight);
        let t = clamp(optical_distance / refraction_zone, 0.0, 1.0);
        let lighting_slope = 2.0 * (1.0 - smootherstep(t));
        return optical_surface_gradient(local, field_weight) * lighting_slope;
    }
    return surface_gradient(local, field_weight);
}

fn unpremultiply(color: vec4f) -> vec3f {
    if (color.a > 0.0001) {
        return color.rgb / color.a;
    }
    return vec3f(0.0);
}

fn apply_ambient(color: vec3f, ambient: f32) -> vec3f {
    // Bound generated SDR highlights before coverage/alpha, while preserving the existing
    // response of colour-managed tints with legitimate extended-range components.
    let input_is_sdr = min(color.r, min(color.g, color.b)) >= 0.0
        && max(color.r, max(color.g, color.b)) <= 1.0;
    let lit = color * ambient;
    return select(lit, clamp(lit, vec3f(0.0), vec3f(1.0)), input_is_sdr);
}

fn luma(color: vec3f) -> f32 {
    return dot(color, vec3f(0.299, 0.587, 0.114));
}

fn sample_chroma_simple(coord: vec2f, chroma_offset: vec2f, center_sample: vec4f) -> vec3f {
    if (length(chroma_offset) < 0.0001) {
        return unpremultiply(center_sample);
    }
    let forward = unpremultiply(sample_depth(coord + chroma_offset));
    let backward = unpremultiply(sample_depth(coord - chroma_offset));
    let center = unpremultiply(center_sample);
    return vec3f(forward.r, center.g, backward.b);
}

fn sample_chroma_full(coord: vec2f, chroma_offset: vec2f, center_sample: vec4f) -> vec3f {
    if (length(chroma_offset) < 0.0001) {
        return unpremultiply(center_sample);
    }
    let red = unpremultiply(sample_depth(coord + chroma_offset));
    let orange = unpremultiply(sample_depth(coord + chroma_offset * (2.0 / 3.0)));
    let yellow = unpremultiply(sample_depth(coord + chroma_offset * (1.0 / 3.0)));
    let green = unpremultiply(center_sample);
    let cyan = unpremultiply(sample_depth(coord - chroma_offset * (1.0 / 3.0)));
    let blue = unpremultiply(sample_depth(coord - chroma_offset * (2.0 / 3.0)));
    let purple = unpremultiply(sample_depth(coord - chroma_offset));
    return vec3f(
        red.r / 3.5 + orange.r / 3.5 + yellow.r / 3.5 + purple.r / 7.0,
        orange.g / 7.0 + yellow.g / 3.5 + green.g / 3.5 + cyan.g / 3.5,
        cyan.b / 3.0 + blue.b / 3.0 + purple.b / 3.0,
    );
}

fn sample_chroma(coord: vec2f, chroma_offset: vec2f, center_sample: vec4f) -> vec3f {
    if (params.chromatic_aberration_mode == 1.0) {
        return sample_chroma_full(coord, chroma_offset, center_sample);
    }
    return sample_chroma_simple(coord, chroma_offset, center_sample);
}

fn compute_content_normal(coord: vec2f, center_sample: vec4f) -> vec3f {
    let h = params.sample_step;
    let center = luma(unpremultiply(center_sample));
    let right = luma(unpremultiply(sample_depth(coord + vec2f(h, 0.0))));
    let down = luma(unpremultiply(sample_depth(coord + vec2f(0.0, h))));
    let gradient = vec2f(right - center, down - center) / max(h, 0.0001);
    return normalize(vec3f(gradient, 1.0));
}

fn apply_color_grading(input: vec3f, applied_white_point: f32) -> vec3f {
    var color = input;
    if (params.chroma_multiplier != 1.0) {
        let lin = to_linear_srgb(color);
        let luminance = dot(lin, vec3f(0.2126, 0.7152, 0.0722));
        var chroma_scale = params.chroma_multiplier;
        if (params.chroma_multiplier > 1.0) {
            let in_gamut = min(lin.r, min(lin.g, lin.b)) >= 0.0
                && max(lin.r, max(lin.g, lin.b)) <= 1.0;
            if (in_gamut) {
                let delta = lin - vec3f(luminance);
                for (var c = 0; c < 3; c++) {
                    if (delta[c] > 0.0) {
                        chroma_scale = min(chroma_scale, (1.0 - luminance) / delta[c]);
                    }
                    if (delta[c] < 0.0) {
                        chroma_scale = min(chroma_scale, -luminance / delta[c]);
                    }
                }
                chroma_scale = max(1.0, chroma_scale);
            } else {
                chroma_scale = 1.0;
            }
        }
        color = from_linear_srgb(mix(vec3f(luminance), lin, chroma_scale));
    }
    if (applied_white_point != 0.0) {
        let target_color = select(vec3f(0.0), vec3f(1.0), applied_white_point > 0.0);
        color = mix(color, target_color, abs(applied_white_point));
    }
    if (params.contrast != 0.0) {
        color = clamp((color - 0.5) * (1.0 + params.contrast) + 0.5, vec3f(0.0), vec3f(1.0));
    }
    return color;
}

fn shade(coord: vec2f) -> vec4f {
    let local = coord - params.material_origin;
    let half_size = params.material_size * 0.5;
    let centered = local - half_size;
    let sd = sd_rounded_corners(local, params.material_size, params.corner_radii);
    let coverage = shape_coverage(sd, params.sample_step * 0.5);
    if (coverage <= 0.0) {
        return vec4f(0.0);
    }

    let dist_to_edge = max(-sd, 0.0);
    var shape_mask = 1.0;
    if (params.edge_softness > 0.0) {
        shape_mask = smootherstep(clamp(dist_to_edge / max(params.edge_softness, 0.0001), 0.0, 1.0));
    }

    let field_weight = optical_field_weight();
    let optical_distance = optical_distance_from_signed_distance(local, sd, field_weight);
    let height_norm = surface_height_norm_from_optical_distance(optical_distance);
    let displacement = refraction_displacement(
        local,
        height_norm,
        select(optical_distance, dist_to_edge, params.edge_refraction_width >= 0.0),
        1.0,
        field_weight,
    );
    let refract_coord = clamp_sample(coord + displacement);

    let corner_weight = abs((centered.x * centered.y) / max(half_size.x * half_size.y, 0.001));
    let chroma_offset = displacement * params.chromatic_aberration_strength * 0.5 * corner_weight;
    let refracted_center = sample_depth(refract_coord);
    let refracted_color = sample_chroma(refract_coord, chroma_offset, refracted_center);

    var ambient = 1.0;
    if (params.ambient_response > 0.0) {
        let response = clamp(params.ambient_response, 0.0, 1.0);
        if (params.fresnel_exponent == 0.0) {
            ambient = 1.0 + response;
        } else {
            let gradient = surface_lighting_gradient(local, sd, field_weight);
            let shape_normal = normalize(vec3f(-gradient.x, -gradient.y, 1.0));
            var normal = shape_normal;
            if (params.content_normal_blend > 0.0) {
                let content_normal = compute_content_normal(refract_coord, refracted_center);
                normal = normalize(mix(shape_normal, content_normal, params.content_normal_blend));
            }
            let fresnel_base = 1.0 - max(dot(normal, vec3f(0.0, 0.0, 1.0)), 0.0);
            var fresnel: f32;
            if (params.fresnel_exponent == 3.0) {
                fresnel = fresnel_base * fresnel_base * fresnel_base;
            } else {
                fresnel = pow(fresnel_base, params.fresnel_exponent);
            }
            ambient = mix(1.0, 1.0 + fresnel, response);
        }
    }
    let graded = clamp(apply_color_grading(refracted_color, params.white_point), vec3f(0.0), vec3f(1.0));
    let tinted = mix(graded, params.tint_color.rgb, params.tint_color.a);
    let final_color = apply_ambient(tinted, ambient);
    let processed = vec4f(final_color * refracted_center.a, refracted_center.a);
    if (shape_mask >= 1.0) {
        if (processed.a > 0.0) {
            return processed * coverage;
        }
        return vec4f(0.0);
    }
    let base_sample = sample_content(clamp_sample(coord), params.content_transform);
    let composed = mix(base_sample, processed, shape_mask);
    if (composed.a > 0.0) {
        return composed * coverage;
    }
    return vec4f(0.0);
}

@fragment
fn fs(@builtin(position) pos: vec4f) -> @location(0) vec4f {
    return shade(pos.xy) * params.opacity;
}
