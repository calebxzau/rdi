@group(0) @binding(1) var hdr:texture_2d<f32>;
fn luminance(v:vec3f)->f32{return dot(v,vec3f(0.299,0.587,0.114));}
@fragment fn fs(v:Fullscreen)->@location(0) vec4f {
    let c=max(textureLoad(hdr,vec2i(v.position.xy),0).rgb,vec3f(0.));
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
