struct PlayerParams { model:mat4x4f, bones:array<mat4x4f,7>, traits:vec4f }
@group(1) @binding(0) var<uniform> player:PlayerParams;
@group(1) @binding(1) var skin:texture_2d<f32>;
@group(1) @binding(2) var cape:texture_2d<f32>;
@group(1) @binding(3) var nearest_sampler:sampler;
struct PlayerOut { @builtin(position) clip:vec4f, @location(0) uv:vec2f, @location(1) normal:vec3f, @location(2) @interpolate(flat) texture:u32, @location(3) world:vec3f }
@vertex fn vs(@location(0) position:vec3f,@location(1) uv:vec2f,@location(2) normal:vec3f,@location(3) part:f32,@location(4) texture:f32)->PlayerOut {
    let transform=player.model*player.bones[u32(part+0.5)];let world=transform*vec4f(position,1.);
    return PlayerOut(params.vp*world,uv,(transform*vec4f(normal,0.)).xyz,u32(texture+0.5),world.xyz);
}
@fragment fn fs(v:PlayerOut)->@location(0) vec4f {
    if params.viewport.z>0.5 && v.world.y<2.36 {discard;}
    var texel:vec4f;
    if v.texture==0u {texel=textureSampleLevel(skin,nearest_sampler,v.uv/vec2f(textureDimensions(skin)),0.);}
    else {
        if player.traits.x<0.5 {discard;}
        texel=textureSampleLevel(cape,nearest_sampler,v.uv/vec2f(textureDimensions(cape)),0.);
    }
    if texel.a<0.05 {discard;}
    let normal=normalize(v.normal);
    let wrapped=clamp((dot(normal,params.sun.xyz)+0.4)/1.4,0.,1.);
    let sky_light=0.78+0.22*normal.y;
    let light=vec3f(0.33,0.40,0.56)*sky_light*1.35+vec3f(1.70,1.18,0.66)*wrapped;
    return vec4f(texel.rgb*light,texel.a);
}
