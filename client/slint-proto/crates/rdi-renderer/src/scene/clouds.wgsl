@group(1) @binding(0) var cloud_texture:texture_2d<f32>;
@group(1) @binding(1) var cloud_sampler:sampler;
struct CloudVertex { @location(0) position:vec3f,@location(1) uv:vec2f,@location(2) color:vec4f,@location(3) normal:vec3f }
struct CloudOut { @builtin(position) clip:vec4f,@location(0) uv:vec2f,@location(1) color:vec4f,@location(2) normal:vec3f,@location(3) relative:vec3f }
@vertex fn vs(v:CloudVertex)->CloudOut {
 let p=vec3f((v.position.x-params.cloud.x)*12.,v.position.y+params.cloud.z,(v.position.z-params.cloud.y)*12.);
 return CloudOut(params.cloud_vp*vec4f(p,1.),v.uv,v.color,v.normal,p);
}
@fragment fn fs(v:CloudOut)->@location(0) vec4f {
 let texel=textureSample(cloud_texture,cloud_sampler,v.uv);if texel.a<0.1 {discard;}
 let c=texel*v.color;let fade=1.-smoothstep(360.,560.,length(v.relative.xz));if c.a*fade<0.01 {discard;}
 let normal=normalize(v.normal);let view=normalize(v.relative);let ndotl=dot(normal,params.sun.xyz);
 let warmth=clamp(pow(alignment(view),2.2)*0.85+max(ndotl,0.)*0.35,0.,1.);
 let cool=to_linear(vec3f(0.82,0.87,0.96));let warm=to_linear(vec3f(1.,0.86,0.74));
 let light=mix(cool,warm,warmth)+warm*pow(max(dot(view,params.sun.xyz),0.),6.)*0.5;
 return vec4f(c.rgb*light,c.a*fade);
}
