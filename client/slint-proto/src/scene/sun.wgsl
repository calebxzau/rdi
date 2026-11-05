@group(2) @binding(0) var sun_texture:texture_2d<f32>;
@group(2) @binding(1) var sun_sampler:sampler;
struct SunOut { @builtin(position) clip:vec4f,@location(0) uv:vec2f }
@vertex fn vs(@builtin(vertex_index) index:u32)->SunOut {
 let points=array<vec2f,6>(vec2f(-1.,-1.),vec2f(1.,-1.),vec2f(1.,1.),vec2f(-1.,-1.),vec2f(1.,1.),vec2f(-1.,1.));
 let local=points[index];let normal=-params.sun.xyz;let right=normalize(cross(vec3f(0.,1.,0.),normal));let up=cross(normal,right);
 let position=params.eye.xyz+params.sun.xyz*600.+(right*local.x+up*local.y)*40.;
 return SunOut(params.vp*vec4f(position,1.),local*0.5+0.5);
}
@fragment fn fs(v:SunOut)->@location(0) vec4f {
 let centered=v.uv-0.5;let duv=centered*1.8+0.5;var disc=vec4f(0.);
 if all(duv>=vec2f(0.)) && all(duv<=vec2f(1.)){disc=textureSampleLevel(sun_texture,sun_sampler,duv,0.);}
 let square=max(abs(centered.x),abs(centered.y));let coverage=1.-smoothstep(0.20,0.27,square);
 let detail=max(disc.r,max(disc.g,disc.b));let color=vec3f(1.,0.80,0.54)+disc.rgb*0.35+detail*0.15;
 let radial=max(1.-length(centered)*2.,0.);let halo=pow(radial,3.)*0.65;let alpha=max(coverage,halo);if alpha<0.01{discard;}
 return vec4f(color*coverage*2.+vec3f(1.,0.66,0.36)*halo,alpha);
}
