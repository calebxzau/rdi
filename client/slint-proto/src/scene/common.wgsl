// Display-authored colors enter linear HDR once; only the final post pass encodes sRGB.
struct Params { vp: mat4x4f, inverse_vp: mat4x4f, eye: vec4f, sun: vec4f, shadow_vp: mat4x4f, lighting: vec4f, viewport:vec4f, reflection_vp:mat4x4f, cloud_vp:mat4x4f, cloud:vec4f, animation:vec4f }
@group(0) @binding(0) var<uniform> params: Params;
fn to_linear(v:vec3f)->vec3f {return select(pow((v+0.055)/1.055,vec3f(2.4)),v/12.92,v<=vec3f(0.04045));}
fn to_srgb(v:vec3f)->vec3f {return select(1.055*pow(max(v,vec3f(0.)),vec3f(1./2.4))-0.055,12.92*v,v<vec3f(0.0031308));}
fn alignment(direction:vec3f)->f32 {if length(direction.xz)<0.0001{return 0.5;}return dot(normalize(direction.xz),normalize(params.sun.xz))*0.5+0.5;}
fn sky(ray:vec3f)->vec3f {
    let up=clamp(ray.y,-1.,1.);let height=max(up,0.);let a=alignment(ray);
    let upper=mix(vec3f(0.30,0.50,0.82),vec3f(0.56,0.60,0.80),pow(a,3.)*0.55);
    var s=mix(upper,vec3f(0.52,0.66,0.88),pow(clamp(1.-height/0.45,0.,1.),2.2));
    let horizon=mix(vec3f(0.72,0.79,0.90),vec3f(1.,0.82,0.56),pow(a,2.));
    s=mix(s,horizon,pow(clamp(1.-height/0.20,0.,1.),2.));
    let golden=pow(a,3.)*pow(clamp(1.-height/0.45,0.,1.),2.);
    s=mix(s,vec3f(1.,0.76,0.46),golden*0.85);
    let sd=max(dot(ray,params.sun.xyz),0.);
    s+=vec3f(1.,0.68,0.36)*(pow(sd,8.)*0.30+pow(sd,48.)*0.30+pow(sd,400.)*1.2);
    return mix(s,horizon*0.82,smoothstep(0.,0.35,-up));
}
fn atmosphere_from(color:vec3f,start:vec3f,position:vec3f)->vec3f {
    let delta=position-start;let climb=delta.y/40.;var profile=1.-climb*0.5;
    if abs(climb)>0.0001{profile=(1.-exp(-climb))/climb;}
    let optical=0.0026*exp(-(start.y-2.35)/40.)*length(delta)*profile;
    let haze=to_linear(mix(vec3f(0.58,0.66,0.82),vec3f(0.88,0.76,0.66),pow(alignment(delta),6.)));
    let hazed=mix(haze,color,exp(-optical));
    return mix(hazed,to_linear(max(sky(normalize(delta)),vec3f(0.))),smoothstep(650.,950.,length(delta)));
}
fn atmosphere(color:vec3f,position:vec3f)->vec3f {return atmosphere_from(color,params.eye.xyz,position);}
fn reflected_atmosphere(color:vec3f,position:vec3f)->vec3f {
    let span=position.y-params.eye.y;var start=position;
    if span>0.0001 {start=mix(params.eye.xyz,position,clamp((2.35-params.eye.y)/span,0.,1.));}
    let dy=position.y-start.y;var inside=select(0.,1.,start.y>=2. && start.y<=10.);
    if abs(dy)>=0.0001 {inside=abs(clamp((10.-start.y)/dy,0.,1.)-clamp((2.-start.y)/dy,0.,1.));}
    let reach=1.-smoothstep(-120.,-50.,0.5*(start.z+position.z));
    let optical=0.075*0.45*inside*length(position-start)*reach;
    return atmosphere_from(mix(vec3f(0.30,0.34,0.44),color,exp(-optical)),start,position);
}
struct Fullscreen { @builtin(position) position:vec4f }
@vertex fn full_vs(@builtin(vertex_index) i:u32)->Fullscreen {
    let p=array<vec2f,3>(vec2f(-1.,-1.),vec2f(3.,-1.),vec2f(-1.,3.));
    return Fullscreen(vec4f(p[i],1.,1.));
}
