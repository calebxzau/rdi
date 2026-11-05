struct Vertex { @location(0) position:vec3f,@location(1) normal:vec3f,@location(2) color:vec3f,@location(3) kind:f32,@location(4) uv:vec2f,@location(5) ao:f32 }
struct Out { @builtin(position) clip:vec4f,@location(0) world:vec3f,@location(1) normal:vec3f,@location(2) color:vec3f,@location(3) ao:f32 }
@vertex fn vs(v:Vertex)->Out {return Out(params.vp*vec4f(v.position,1.),v.position,v.normal,v.color,v.ao);}
@fragment fn fs(v:Out)->@location(0) vec4f {
    let n=normalize(v.normal);let diffuse=max(dot(n,params.sun.xyz),0.);
    let ambient=vec3f(0.33,0.40,0.56)*(0.68+0.32*n.y)*mix(1.,v.ao,0.55);
    let direct=vec3f(1.70,1.18,0.66)*diffuse;
    return vec4f(atmosphere(v.color*(ambient+direct),v.world),1.);
}
