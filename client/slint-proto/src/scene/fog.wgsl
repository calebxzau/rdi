struct Wind { coarse:vec4f, detail:vec4f }
@group(1) @binding(0) var scene_depth:texture_depth_2d;
@group(1) @binding(1) var noise_volume:texture_3d<f32>;
@group(1) @binding(2) var noise_sampler:sampler;
@group(1) @binding(3) var fog_shadow:texture_depth_2d;
@group(1) @binding(4) var fog_comparison:sampler_comparison;
@group(1) @binding(5) var<uniform> wind:Wind;
const FOG_BOTTOM:f32=2.;
const FOG_TOP:f32=10.;
const FOG_SOFTNESS:f32=3.;
const FOG_MAX_RISE:f32=14.;
const FOG_DENSITY:f32=0.075;
const FOG_AMBIENT=vec3f(0.30,0.34,0.44);
const FOG_SUN=vec3f(0.52,0.38,0.22);
fn fog_top(z:f32)->f32 {return FOG_TOP+clamp((-160.-z)*0.05,0.,FOG_MAX_RISE);}
fn fog_density(p:vec3f)->f32 {
    let layer=smoothstep(FOG_BOTTOM-FOG_SOFTNESS,FOG_BOTTOM+FOG_SOFTNESS,p.y)*(1.-smoothstep(fog_top(p.z)-FOG_SOFTNESS,fog_top(p.z)+FOG_SOFTNESS,p.y));
    let envelope=layer*(1.-smoothstep(-120.,-50.,p.z));
    if envelope<=0. {return 0.;}
    let coarse=textureSampleLevel(noise_volume,noise_sampler,p*vec3f(1./420.,1./40.,1./180.)+wind.coarse.xyz,0.).r;
    let detail=textureSampleLevel(noise_volume,noise_sampler,p*vec3f(1./140.,1./18.,1./70.)+wind.detail.xyz,0.).r;
    let banks=smoothstep(0.40,0.72,coarse*0.7+detail*0.3);
    let over_lake=1.-smoothstep(0.6,1.,length((p.xz-vec2f(70.,-185.))/vec2f(125.,40.)));
    return FOG_DENSITY*envelope*banks*mix(1.,0.3,over_lake);
}
fn fog_phase(cos_theta:f32)->f32 {
    let g=0.35;let denominator=1.+g*g-2.*g*cos_theta;
    return (1.-g*g)/(denominator*sqrt(denominator));
}
@fragment fn fs(v:Fullscreen)->@location(0) vec4f {
    // D3D depth is already 0..1; screen UV is top-left. GL jitter used bottom-left pixels.
    let low_size=params.viewport.xy*0.5;
    let uv=v.position.xy/low_size;
    let depth=textureLoad(scene_depth,vec2i(uv*params.viewport.xy),0);
    let surface=params.inverse_vp*vec4f(uv.x*2.-1.,1.-uv.y*2.,depth,1.);
    let ray=surface.xyz/surface.w-params.eye.xyz;
    var distance=length(ray);let direction=ray/distance;
    if depth>=0.9999 {distance=900.;}
    if direction.y < -0.0001 {distance=min(distance,(2.35-params.eye.y)/direction.y);}
    let slab_low=FOG_BOTTOM-FOG_SOFTNESS;let slab_high=FOG_TOP+FOG_MAX_RISE+FOG_SOFTNESS;
    var start=0.;var end=0.;
    if abs(direction.y)<0.00001 {
        end=select(0.,distance,params.eye.y>=slab_low && params.eye.y<=slab_high);
    } else {
        let a=(slab_low-params.eye.y)/direction.y;let b=(slab_high-params.eye.y)/direction.y;
        start=max(min(a,b),0.);end=min(max(a,b),distance);
    }
    if end<=start {return vec4f(0.,0.,0.,1.);}
    let segment=end-start;let count=u32(clamp(ceil(segment/6.),6.,24.));
    let step_length=segment/f32(count);
    let gl_pixel=vec2f(v.position.x,low_size.y-v.position.y);
    let jitter=fract(52.9829189*fract(dot(gl_pixel,vec2f(0.06711056,0.00583715))));
    var t=start+step_length*jitter;
    let sun_light=FOG_SUN*fog_phase(dot(direction,params.sun.xyz));
    let haze=to_linear(mix(vec3f(0.58,0.66,0.82),vec3f(0.88,0.76,0.66),pow(alignment(direction),6.)));
    var transmittance=1.;var scattered=vec3f(0.);
    for(var i=0u;i<24u;i++) {
        if i>=count || transmittance<0.01 {break;}
        let p=params.eye.xyz+direction*t;let density=fog_density(p);
        if density>0. {
            let step_transmittance=exp(-density*step_length);
            let shadow_clip=params.shadow_vp*vec4f(p,1.);let clip=shadow_clip.xyz/shadow_clip.w;
            let coord=vec3f(clip.x*0.5+0.5,0.5-clip.y*0.5,clip.z);
            var visibility=1.;
            if all(coord>=vec3f(0.)) && all(coord<=vec3f(1.)) {
                visibility=textureSampleCompareLevel(fog_shadow,fog_comparison,coord.xy,coord.z-0.5/params.lighting.y);
            }
            let height_shade=mix(0.72,1.,clamp((p.y-FOG_BOTTOM)/(fog_top(p.z)-FOG_BOTTOM),0.,1.));
            var light=(FOG_AMBIENT+sun_light*visibility)*height_shade;
            let climb=(p.y-params.eye.y)/40.;var profile=1.-climb*0.5;
            if abs(climb)>0.0001 {profile=(1.-exp(-climb))/climb;}
            let optical=0.0026*exp(-(params.eye.y-2.35)/40.)*length(p-params.eye.xyz)*profile;
            light=mix(haze,light,exp(-optical));
            scattered+=transmittance*(1.-step_transmittance)*light;
            transmittance*=step_transmittance;
        }
        t+=step_length;
    }
    return vec4f(scattered,transmittance);
}
