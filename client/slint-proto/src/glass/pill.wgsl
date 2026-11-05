// Ported from LIQUID_GLASS_SKSL (client/ui LiquidGlass.kt): one rounded glass rect with a press glow.
// Used for the selected tab. Field order must match `PillParams` in mod.rs.

struct Params {
    // left, top, right, bottom
    shape_rect: vec4f,
    tint: vec4f,
    hairline: vec4f,
    content_transform: vec4f,
    light_direction: vec2f,
    glow_center: vec2f,
    corner_radius: f32,
    bevel: f32,
    taper: f32,
    refractive_index: f32,
    specular_intensity: f32,
    shininess: f32,
    hairline_width: f32,
    glow_radius: f32,
    glow_intensity: f32,
}

@group(0) @binding(0) var<uniform> params: Params;

fn shade(coord: vec2f) -> vec4f {
    let half_size = (params.shape_rect.zw - params.shape_rect.xy) * 0.5;
    let p = coord - (params.shape_rect.xy + half_size);
    let r = min(params.corner_radius, min(half_size.x, half_size.y));
    let d = sd_rounded_rect(p, half_size, r);
    let coverage = clamp(0.5 - d, 0.0, 1.0);
    if (coverage <= 0.0) {
        return vec4f(0.0);
    }
    let dist = max(-d, 0.0);
    let optics = bevel_optics(
        dist, outward_normal(p, half_size, r), params.bevel, params.taper, params.refractive_index,
        params.light_direction, params.specular_intensity, params.shininess,
    );
    var color = sample_content(coord + optics.xy, params.content_transform);
    color = over(vec4f(params.tint.rgb * params.tint.a, params.tint.a), color);
    if (params.glow_intensity > 0.0) {
        let g = (coord - params.glow_center) / params.glow_radius;
        let glow = clamp(params.glow_intensity * exp(-dot(g, g)), 0.0, 1.0);
        color = over(vec4f(glow), color);
    }
    color = over(vec4f(optics.z), color);
    color = with_hairline(color, dist, params.hairline, params.hairline_width);
    return color * coverage;
}

@fragment
fn fs(@builtin(position) pos: vec4f) -> @location(0) vec4f {
    return shade(pos.xy);
}
