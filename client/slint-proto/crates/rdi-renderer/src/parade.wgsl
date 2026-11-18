struct Params { viewport: vec4f }
@group(0) @binding(0) var<uniform> params: Params;
@group(1) @binding(0) var skin: texture_2d<f32>;
@group(1) @binding(1) var nearest_sampler: sampler;
struct Out {
    @builtin(position) clip: vec4f,
    @location(0) uv: vec2f,
    @location(1) normal: vec3f,
}
fn rotate_x(v: vec3f, angle: f32) -> vec3f {
    let c = cos(angle); let s = sin(angle);
    return vec3f(v.x, c*v.y - s*v.z, s*v.y + c*v.z);
}
fn rotate_y(v: vec3f) -> vec3f {
    // Face right, slightly toward the viewer so the skin's face remains visible.
    let c = 0.34202015; let s = 0.9396926;
    return vec3f(c*v.x + s*v.z, v.y, c*v.z - s*v.x);
}
@vertex fn vs(
    @location(0) position: vec3f, @location(1) uv: vec2f,
    @location(2) normal: vec3f, @location(3) part: f32,
    @location(5) pose: vec4f,
) -> Out {
    let id = u32(part + 0.5);
    var local = position;
    var direction = normal;
    if id >= 2u && id <= 5u {
        let arm = id <= 3u;
        let side = select(1., -1., id == 2u || id == 4u);
        let pivot = vec3f(side*select(1.9, pose.w, arm), select(12., 22., arm), 0.);
        let pitch = select(-pose.z, pose.z, id == 2u || id == 5u);
        local = rotate_x(local - pivot, pitch) + pivot;
        direction = rotate_x(direction, pitch);
    }
    local = rotate_y(local);
    let pixel = vec2f(pose.x + local.x*2.625, pose.y - local.y*2.625);
    // Plain RGBA output, matching the glass renderer. Larger z is nearer the viewer.
    let clip = vec4f(pixel/params.viewport.xy*vec2f(2., -2.) + vec2f(-1., 1.), 0.5 - local.z/100., 1.);
    return Out(clip, uv, rotate_y(direction));
}
@fragment fn fs(v: Out) -> @location(0) vec4f {
    let texel = textureSample(skin, nearest_sampler, v.uv/64.);
    if texel.a < 0.05 { discard; }
    let light = 0.72 + 0.28*max(dot(normalize(v.normal), normalize(vec3f(-0.4, 0.8, 0.6))), 0.);
    return vec4f(texel.rgb*light, texel.a);
}
