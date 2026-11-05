@group(1) @binding(0) var normals:texture_2d<f32>;
@group(1) @binding(1) var noise:texture_2d<f32>;
@group(1) @binding(2) var scene_color:texture_2d<f32>;
@group(1) @binding(3) var scene_depth:texture_depth_2d;
@group(1) @binding(4) var reflection:texture_2d<f32>;
@group(1) @binding(5) var repeat_sampler:sampler;
@group(1) @binding(6) var clamp_sampler:sampler;
struct WaterOut { @builtin(position) clip:vec4f,@location(0) world:vec3f,@location(1) reflection_clip:vec4f }
@vertex fn vs(@builtin(vertex_index) i:u32)->WaterOut {
 let corners=array<vec2f,6>(vec2f(-1100.,1100.),vec2f(1100.,1100.),vec2f(1100.,-1100.),vec2f(-1100.,1100.),vec2f(1100.,-1100.),vec2f(-1100.,-1100.));
 let c=corners[i];let time=params.animation.x;
 let p=vec3f(c.x,2.35+sin(c.x*0.8+time*1.4)*0.07+cos(c.y*0.5+time)*0.04,c.y);
 return WaterOut(params.vp*vec4f(p,1.),p,params.reflection_vp*vec4f(p,1.));
}
fn reconstruct(uv:vec2f,depth:f32)->vec3f {
 let p=params.inverse_vp*vec4f(uv.x*2.-1.,1.-uv.y*2.,depth,1.);return p.xyz/p.w;
}
fn depth_at(uv:vec2f)->f32 {
 let size=vec2i(textureDimensions(scene_depth));return textureLoad(scene_depth,clamp(vec2i(uv*vec2f(size)),vec2i(0),size-1),0);
}
fn sun_highlight(normal:vec3f,view:vec3f,light:vec3f)->vec3f {
 let nv=max(dot(normal,view),0.001);let nl=max(dot(normal,light),0.);if nl<=0. {return vec3f(0.);}
 let half_direction=normalize(view+light);let nh=max(dot(normal,half_direction),0.);let vh=max(dot(view,half_direction),0.);
 let roughness=0.12;let alpha=roughness*roughness;let a2=alpha*alpha;
 let denominator=nh*nh*(a2-1.)+1.;let distribution=a2/(3.14159265*denominator*denominator);
 let k=(roughness+1.)*(roughness+1.)/8.;let geometry=(nv/(nv*(1.-k)+k))*(nl/(nl*(1.-k)+k));
 let fresnel=vec3f(0.02)+0.98*pow(1.-vh,5.);
 return min(distribution*geometry*fresnel/(4.*nv*nl)*vec3f(1.,0.84,0.62)*0.08,vec3f(1.8));
}
@fragment fn fs(v:WaterOut)->@location(0) vec4f {
 let distance=length(v.world.xz-params.eye.xz);
 let water_noise=textureSample(noise,repeat_sampler,v.world.xz*0.018).g;
 var base=to_linear(vec3f(0.08,0.30,0.58))*exp((water_noise-0.5)*0.16);
 let near=1.-smoothstep(18.,110.,distance);base*=mix(vec3f(1.),vec3f(0.92,0.98,1.04),near*0.35);
 let view=normalize(params.eye.xyz-v.world);let edge=1.-clamp(view.y,0.,1.);
 let pos=0.032*(v.world.xz+v.world.y*2.)*2.5;let wind=vec2f(0.,-params.animation.y);
 let medium=textureSample(normals,repeat_sampler,pos+wind).rg*2.-1.;
 let small=textureSample(normals,repeat_sampler,pos*4.-wind*2.).rg*2.-1.;
 let big=textureSample(normals,repeat_sampler,pos*0.25-wind*0.5).rg*2.-1.;
 let extra=textureSample(normals,repeat_sampler,pos*0.05-wind*0.05).rg*2.-1.;
 var xy=(medium*1.10+small*0.35+big*1.55+extra*0.85)*(0.27*(1.-0.60*edge));
 let magnitude=min(length(xy),0.98);if magnitude>0. {xy=normalize(xy)*magnitude;} else {xy=vec2f(0.);}
 let normal=normalize(vec3f(xy.x,sqrt(max(1.-dot(xy,xy),0.)),xy.y));
 let nv=clamp(dot(normal,view),0.,1.);let fresnel=0.02+0.98*pow(1.-nv,5.);
 let uv=v.clip.xy/vec2f(textureDimensions(scene_color));
 // Only screen-space offsets are Y-flipped; world-space normal maps retain their original UVs.
 let offset=xy*vec2f(0.012,-0.012);let candidate=clamp(uv+offset,vec2f(0.001),vec2f(0.999));
 let candidate_depth=depth_at(candidate);let candidate_world=reconstruct(candidate,candidate_depth);
 let behind=candidate_depth>v.clip.z+0.0001 && candidate_world.y<=v.world.y+0.15;
 let transmission_uv=select(uv,candidate,candidate_depth>=0.999 || behind);
 let depth=depth_at(transmission_uv);let scene_world=reconstruct(transmission_uv,depth);
 let geometry=depth<0.999 && depth>v.clip.z+0.0001 && scene_world.y<=v.world.y;
 var thickness=0.05;if depth>=0.999 {thickness=24.;} else if geometry {thickness=clamp(length(scene_world-v.world),0.,24.);}
 let absorption=exp(-vec3f(0.22,0.10,0.035)*thickness);
 let transmission=textureSample(scene_color,clamp_sampler,transmission_uv).rgb;
 var tinted=mix(base,transmission,absorption);tinted=mix(transmission,tinted,mix(0.65,1.,fresnel));
 var foam=0.;if geometry {foam=(1.-smoothstep(0.12,1.35,thickness))*smoothstep(0.38,0.68,water_noise);}
 let reflected_direction=reflect(-view,normal);var planar=to_linear(max(sky(reflected_direction),vec3f(0.)));
 let reflection_uv=vec2f(v.reflection_clip.x/v.reflection_clip.w*0.5+0.5,0.5-v.reflection_clip.y/v.reflection_clip.w*0.5);
 if v.reflection_clip.w>0. && all(reflection_uv>=vec2f(0.)) && all(reflection_uv<=vec2f(1.)) {
  let sample_uv=clamp(reflection_uv+offset,vec2f(0.001),vec2f(0.999));
  let lod=clamp(clamp((distance-24.)/88.*2.5,0.,2.5)+edge*0.65,0.,3.5);
  planar=textureSampleLevel(reflection,clamp_sampler,sample_uv,lod).rgb;
 }
 var result=mix(tinted,planar,clamp(0.06+fresnel*0.92,0.,0.92));
 result=mix(result,vec3f(0.82,0.96,0.98),foam*0.42);
 result+=sun_highlight(normal,view,params.sun.xyz)*(1.-smoothstep(80.,180.,distance))*(1.-0.35*edge);
 return vec4f(atmosphere(result,v.world),1.);
}
