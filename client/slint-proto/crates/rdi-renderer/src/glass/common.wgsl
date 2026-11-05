// Shared by every pass: a full-screen triangle and the content texture with its sampler.
// Coordinates are pixels with the origin at the top left, like Skia and @builtin(position).

@group(0) @binding(1) var content: texture_2d<f32>;
@group(0) @binding(2) var content_sampler: sampler;

@vertex
fn vs(@builtin(vertex_index) i: u32) -> @builtin(position) vec4f {
    let p = vec2f(f32((i << 1u) & 2u), f32(i & 2u));
    return vec4f(p * 2.0 - 1.0, 0.0, 1.0);
}

// Samples the background-space `content` at frame pixel `p`. The background and its blurred copies are
// stored in image space; `transform` maps frame pixels to its uv (cover crop): uv = p * xy + zw.
fn sample_content(p: vec2f, transform: vec4f) -> vec4f {
    return textureSampleLevel(content, content_sampler, p * transform.xy + transform.zw, 0.0);
}

fn over(src: vec4f, dst: vec4f) -> vec4f {
    return src + dst * (1.0 - src.a);
}
