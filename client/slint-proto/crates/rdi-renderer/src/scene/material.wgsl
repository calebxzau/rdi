@group(2) @binding(0) var block:texture_2d<f32>;
@group(2) @binding(1) var block_sampler:sampler;
@group(2) @binding(2) var shadow_map:texture_depth_2d;
@group(2) @binding(3) var shadow_sampler:sampler_comparison;
@group(2) @binding(4) var block_magnification_sampler:sampler;
const LEGACY_SAMPLER:bool=false;
@fragment fn fs(v:Out,@builtin(front_facing) front:bool)->@location(0) vec4f {
 let scale=vec2f(1.,batch.traits.w);let uv=fract(v.uv)*scale;
 let dx=dpdx(v.uv)*scale;let dy=dpdy(v.uv)*scale;
 let size=vec2f(textureDimensions(block));
 let px=length(dx*size);let py=length(dy*size);
 let major=max(px,py);let minor=min(px,py);
 let taps=min(ceil(major/max(minor,0.00001)),8.);
 let footprint=major/max(taps,1.);
 var texel:vec4f;
 if !LEGACY_SAMPLER && footprint<=1. {
  // EXT_texture_filter_anisotropic's footprint model with nearest magnification.
  // Native WebGPU anisotropy requires linear magnification, which darkens cutout edges.
  let axis=select(dy,dx,px>py);texel=vec4f(0.);
  for(var i=1.;i<=max(taps,1.);i+=1.) {
   texel+=textureSampleLevel(block,block_magnification_sampler,uv+axis*(i/(max(taps,1.)+1.)-0.5),0.);
  }
  texel/=max(taps,1.);
 }
 else {texel=textureSampleGrad(block,block_sampler,uv,dx,dy);}
 if batch.traits.x>0.5 && texel.a<0.1 {discard;}
 if params.viewport.z>0.5 && v.world.y<2.36 {discard;}
 var normal=normalize(v.normal);let double_sided=batch.traits.y>0.5;
 if double_sided && !front && abs(normal.y)<0.5 {normal=-normal;}
 let diffuse=max(dot(normal,params.sun.xyz),0.);let view=normalize(v.world-params.eye.xyz);
 let translucency=select(0.,pow(max(dot(view,params.sun.xyz),0.),3.)*0.45,double_sided);
 var visibility=1.;let clip=v.shadow.xyz/v.shadow.w;
 let suv=vec2f(clip.x*0.5+0.5,0.5-clip.y*0.5);
 if diffuse+translucency>0. && v.shadow.w>0. && clip.z>0. && clip.z<1. && all(suv>=vec2f(0.)) && all(suv<=vec2f(1.)) {
  let bias=mix(0.06,0.30,1.-diffuse)/params.lighting.y;visibility=0.;
  for(var x=0;x<2;x++){for(var y=0;y<2;y++){
   let offset=(vec2f(f32(x),f32(y))-0.5)/2048.;
   visibility+=textureSampleCompareLevel(shadow_map,shadow_sampler,suv+offset,clip.z-bias);
  }}visibility*=0.25;
 }
 let ambient=vec3f(0.33,0.40,0.56)*(0.68+0.32*normal.y)*mix(1.,v.ao,0.55);
 let direct=vec3f(1.70,1.18,0.66)*(diffuse+translucency)*visibility;
 var lit=v.color*texel.rgb*(ambient+direct);
 if batch.traits.z>0.5 {let brightness=max(texel.r,max(texel.g,texel.b));lit+=v.color*vec3f(1.8,0.72,0.16)*smoothstep(0.25,0.75,brightness);}
 if params.lighting.z==1. {return texel;}
 if params.lighting.z==2. {return vec4f(v.color,1.);}
 if params.lighting.z==3. {return vec4f(v.ao,diffuse,visibility,translucency);}
 if params.lighting.z==4. {return vec4f(length(dpdx(v.uv)*scale),length(dpdy(v.uv)*scale),0.,1.);}
 if params.lighting.z==5. {return vec4f(select(0.,1.,double_sided && normal.y>0.5),translucency,texel.a,1.);}
 if params.viewport.z>0.5 {return vec4f(reflected_atmosphere(lit,v.world),1.);}
 return vec4f(atmosphere(lit,v.world),1.);
}
@fragment fn shadow_fs(v:Out) {
 if v.kind!=0. {discard;}
 let texel=textureSampleLevel(block,block_sampler,fract(v.uv)*vec2f(1.,batch.traits.w),0.);
 if batch.traits.x>0.5 && texel.a<0.1 {discard;}
}
