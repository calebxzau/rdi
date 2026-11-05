// Ported from GLASS_TILE_GRID_SKSL (client/ui GlassTileGrid.kt): every tile's glass and shadow in one
// pass. Field order must match `GridParams` in mod.rs.

struct Params {
    tint: vec4f,
    hairline: vec4f,
    content_transform: vec4f,
    origin: vec2f,
    pitch: vec2f,
    tile_size: vec2f,
    light_direction: vec2f,
    shadow_sigma: vec2f,
    shadow_offset_y: vec2f,
    rows: f32,
    count: f32,
    corner_radius: f32,
    bevel: f32,
    taper: f32,
    refractive_index: f32,
    specular_intensity: f32,
    shininess: f32,
    hairline_width: f32,
    hover_index: f32,
    hover_progress: f32,
    hover_specular_boost: f32,
    press_index: f32,
    press_scale: f32,
    shadow_alpha: f32,
    edge_shadow_sigma: f32,
    edge_shadow_alpha: f32,
    opacity: f32,
}

@group(0) @binding(0) var<uniform> params: Params;

fn blurred_edge(d: f32, sigma: f32) -> f32 {
    return 1.0 / (1.0 + exp(min(1.702 * d / sigma, 40.0)));
}

// 按行列间距取模得到当前格，只需检查当前格及右、下、右下三个邻格；投影用logistic近似高斯模糊后的边缘
fn shade(coord: vec2f) -> vec4f {
    let cell0 = floor((coord - params.origin) / params.pitch);
    var glass = vec4f(0.0);
    var glass_coverage = 0.0;
    var ambient = 0.0;
    var edge = 0.0;
    for (var i = 0; i < 4; i++) {
        let fi = f32(i);
        let cell = cell0 + vec2f(fi % 2.0, floor(fi * 0.5));
        let index = cell.x * params.rows + cell.y;
        if (cell.x < 0.0 || cell.y < 0.0 || cell.y >= params.rows || index >= params.count) {
            continue;
        }
        let scale = select(1.0, params.press_scale, index == params.press_index);
        let hover = select(0.0, params.hover_progress, index == params.hover_index);
        let half_size = params.tile_size * 0.5 * scale;
        let r = min(params.corner_radius * scale, min(half_size.x, half_size.y));
        let p = coord - (params.origin + cell * params.pitch + params.tile_size * 0.5);
        let d = sd_rounded_rect(p, half_size, r);
        let lift = mix(params.shadow_offset_y.x, params.shadow_offset_y.y, hover);
        let sigma = mix(params.shadow_sigma.x, params.shadow_sigma.y, hover);
        ambient = max(ambient, blurred_edge(sd_rounded_rect(p - vec2f(0.0, lift), half_size, r), sigma));
        edge = max(edge, blurred_edge(d, params.edge_shadow_sigma));
        let coverage = clamp(0.5 - d, 0.0, 1.0);
        if (coverage > 0.0) {
            let dist = max(-d, 0.0);
            let optics = bevel_optics(
                dist, outward_normal(p, half_size, r), params.bevel * scale, params.taper * scale,
                params.refractive_index, params.light_direction,
                params.specular_intensity * (1.0 + params.hover_specular_boost * hover), params.shininess,
            );
            var color = sample_content(coord + optics.xy, params.content_transform);
            color = over(vec4f(params.tint.rgb * params.tint.a, params.tint.a), color);
            color = over(vec4f(optics.z), color);
            glass = with_hairline(color, dist, params.hairline, params.hairline_width);
            glass_coverage = coverage;
        }
    }
    let shadow = 1.0 - (1.0 - ambient * params.shadow_alpha) * (1.0 - edge * params.edge_shadow_alpha);
    return glass * glass_coverage + vec4f(0.0, 0.0, 0.0, shadow) * (1.0 - glass_coverage);
}

@fragment
fn fs(@builtin(position) pos: vec4f) -> @location(0) vec4f {
    return shade(pos.xy) * params.opacity;
}
