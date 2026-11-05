// One direction of a separable Gaussian blur in background image space; `size` is the output size,
// `sigma` in output pixels. The input is sampled by normalised uv, so it may have another resolution.

struct Params {
    size: vec2f,
    direction: vec2f,
    sigma: f32,
}

@group(0) @binding(0) var<uniform> params: Params;

@fragment
fn fs(@builtin(position) pos: vec4f) -> @location(0) vec4f {
    let uv = pos.xy / params.size;
    let radius = ceil(params.sigma * 3.0);
    let k = -0.5 / (params.sigma * params.sigma);
    var color = textureSampleLevel(content, content_sampler, uv, 0.0);
    var weight = 1.0;
    // Pair adjacent Gaussian taps with linear filtering. Radius is 3 sigma.
    for (var i = 1; i <= 256; i += 2) {
        let a_offset = f32(i);
        if (a_offset > radius) {
            break;
        }
        let b_offset = a_offset + 1.0;
        let a = exp(k * a_offset * a_offset);
        let b = select(0.0, exp(k * b_offset * b_offset), b_offset <= radius);
        let w = a + b;
        let offset = params.direction * (a_offset + b / w) / params.size;
        color += (textureSampleLevel(content, content_sampler, uv + offset, 0.0)
            + textureSampleLevel(content, content_sampler, uv - offset, 0.0)) * w;
        weight += 2.0 * w;
    }
    return color / weight;
}
