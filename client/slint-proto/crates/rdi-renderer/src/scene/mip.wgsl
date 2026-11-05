@group(0) @binding(0) var source:texture_2d<f32>;
@group(0) @binding(1) var linear_sampler:sampler;
struct Out { @builtin(position) position:vec4f,@location(0) uv:vec2f }
@vertex fn vs(@builtin(vertex_index) i:u32)->Out {
 let p=array<vec2f,3>(vec2f(-1.,-1.),vec2f(3.,-1.),vec2f(-1.,3.));
 return Out(vec4f(p[i],0.,1.),vec2f(p[i].x*0.5+0.5,0.5-p[i].y*0.5));
}
@fragment fn fs(v:Out)->@location(0) vec4f {return textureSampleLevel(source,linear_sampler,v.uv,0.);}
