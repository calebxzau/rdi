fn bayer2(p:vec2f)->f32 {let c=0.5*floor(p);return fract(1.5*fract(c.y)+c.x);}
fn bayer4(p:vec2f)->f32{return 0.25*bayer2(0.5*p)+bayer2(p);}
fn bayer8(p:vec2f)->f32{return 0.25*bayer4(0.5*p)+bayer2(p);}
@fragment fn fs(v:Fullscreen)->@location(0) vec4f {
    // D3D framebuffer origin is top left; clip-space Y points up and depth is 0..1.
    let ndc=vec2f(v.position.x/params.viewport.x*2.-1.,1.-v.position.y/params.viewport.y*2.);
    let far=params.inverse_vp*vec4f(ndc,1.,1.);
    let ray=normalize(far.xyz/far.w-params.eye.xyz);
    let display=sky(ray)+(bayer8(vec2f(v.position.x,params.viewport.y-v.position.y))-0.5)/128.;
    return vec4f(to_linear(max(display,vec3f(0.))),1.);
}
