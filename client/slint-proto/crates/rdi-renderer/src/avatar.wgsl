// U4e2: one player turning and walking in place for the skin preview (Compose PlayerModel with
// autoRotate and animateWalk). Output is RGBA on a transparent background.
struct Params { vp: mat4x4f, model: mat4x4f, bones: array<mat4x4f, 7>, traits: vec4f }
@group(0) @binding(0) var<uniform> params: Params;
@group(0) @binding(1) var skin: texture_2d<f32>;
@group(0) @binding(2) var cape: texture_2d<f32>;
@group(0) @binding(3) var nearest_sampler: sampler;
struct Out {
    @builtin(position) clip: vec4f,
    @location(0) uv: vec2f,
    @location(1) normal: vec3f,
    @location(2) shade: f32,
    @location(3) @interpolate(flat) texture: u32,
}
@vertex fn vs(
    @location(0) position: vec3f, @location(1) uv: vec2f, @location(2) normal: vec3f,
    @location(3) part: f32, @location(4) shade: f32, @location(5) texture: f32,
) -> Out {
    let transform = params.model * params.bones[u32(part + 0.5)];
    let world = transform * vec4f(position, 1.);
    return Out(params.vp * world, uv, (transform * vec4f(normal, 0.)).xyz, shade, u32(texture + 0.5));
}
@fragment fn fs(v: Out) -> @location(0) vec4f {
    var texel: vec4f;
    if v.texture == 0u {
        texel = textureSampleLevel(skin, nearest_sampler, v.uv / vec2f(textureDimensions(skin)), 0.);
    } else {
        if params.traits.x < 0.5 { discard; }
        texel = textureSampleLevel(cape, nearest_sampler, v.uv / vec2f(textureDimensions(cape)), 0.);
    }
    if texel.a < 0.05 { discard; }
    let diffuse = max(dot(normalize(v.normal), normalize(vec3f(-0.35, 0.55, 0.75))), 0.);
    let light = min(v.shade, 1.) * (0.78 + 0.3 * diffuse);
    return vec4f(texel.rgb * light, texel.a);
}
