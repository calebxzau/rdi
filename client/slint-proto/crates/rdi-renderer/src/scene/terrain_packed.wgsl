// Batch-local signed fixed point. Position units and the per-batch origin are exact binary fractions.
struct BatchParams { origin: vec4f }
@group(1) @binding(0) var<uniform> batch: BatchParams;
struct Vertex { @location(0) position:vec4i,@location(1) normal:vec4f,@location(2) color_ao:vec4f,@location(3) uv:vec2f }
struct Out { @builtin(position) clip:vec4f,@location(0) world:vec3f,@location(1) normal:vec3f,@location(2) color:vec3f,@location(3) ao:f32 }
@vertex fn vs(v:Vertex)->Out {let p=vec3f(v.position.xyz)/16.0+batch.origin.xyz;return Out(params.vp*vec4f(p,1.),p,v.normal.xyz,v.color_ao.rgb,v.color_ao.a);}
@fragment fn fs(v:Out)->@location(0) vec4f {
    let n=normalize(v.normal);let diffuse=max(dot(n,params.sun.xyz),0.);
    let ambient=vec3f(0.33,0.40,0.56)*(0.68+0.32*n.y)*mix(1.,v.ao,0.55);
    let direct=vec3f(1.70,1.18,0.66)*diffuse;
    return vec4f(atmosphere(v.color*(ambient+direct),v.world),1.);
}
