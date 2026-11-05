@group(1) @binding(0) var hdr:texture_2d<f32>;
@group(1) @binding(1) var atlas:texture_2d<f32>;
@group(1) @binding(2) var post_sampler:sampler;
fn bloom_sample(lod:f32,coord:vec2f,offset:vec2f)->vec3f {
    let scale=exp2(lod);
    let uv=clamp(coord/scale+offset,offset,vec2f(1./scale)+offset);
    var value=textureSampleLevel(atlas,post_sampler,vec2f(uv.x,1.-uv.y),0.).rgb;
    value*=value;value*=value;return value*128.;
}
fn bloom_mix(color:vec3f,uv:vec2f)->vec3f {
    let coord=vec2f(uv.x,1.-uv.y);
    var blur=bloom_sample(2.,coord,vec2f(0.,0.));
    blur+=bloom_sample(3.,coord,vec2f(0.,0.26));
    blur+=bloom_sample(4.,coord,vec2f(0.135,0.26));
    blur+=bloom_sample(5.,coord,vec2f(0.2075,0.26));
    blur+=bloom_sample(6.,coord,vec2f(0.135,0.3325));
    blur+=bloom_sample(7.,coord,vec2f(0.160625,0.3325));
    blur+=bloom_sample(8.,coord,vec2f(0.1784375,0.3325));
    return mix(color,blur*0.14,0.12);
}
fn luminance(v:vec3f)->f32{return dot(v,vec3f(0.299,0.587,0.114));}
@fragment fn fs(v:Fullscreen)->@location(0) vec4f {
    let c=max(bloom_mix(textureLoad(hdr,vec2i(v.position.xy),0).rgb,v.position.xy/params.viewport.xy),vec3f(0.));
    let lum=luminance(c);let a=1.12;let hm=pow(8.,a);let mi=pow(0.25,a);
    let b=(-mi+hm*0.25)/((hm-mi)*0.25);let d=(hm*mi-hm*0.25*mi)/((hm-mi)*0.25);
    let power=pow(c,vec3f(a));var out=to_srgb(power/(power*b+d));
    out=mix(out,pow(c,vec3f(1./2.2)),(1.-smoothstep(0.,0.1,lum))*0.75*(0.55-abs(1.05-a))/0.55);
    out=mix(out,vec3f(1.),smoothstep(0.,16.,lum));
    out=clamp(mix(out,vec3f(luminance(out)),(1.-smoothstep(0.,0.1,lum))*0.25),vec3f(0.),vec3f(1.));
    let gray=(out.r+out.g+out.b)/3.;let mn=min(out.r,min(out.g,out.b));let mx=max(out.r,max(out.g,out.b));
    let sat=(1.-(mx-mn))*(1.-mx)*gray*5.;let light=vec3f((mn+mx)*0.5);
    out=mix(out,mix(out,light,-0.12),sat);
    out=mix(out,light,(1.-light)*0.88/2.*0.12);
    out=out*1.11-gray*0.11;
    return vec4f(out,1.);
}
