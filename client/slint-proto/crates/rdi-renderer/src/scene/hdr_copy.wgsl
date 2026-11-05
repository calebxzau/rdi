@group(1) @binding(0) var raw_hdr:texture_2d<f32>;
@group(1) @binding(1) var valley_fog:texture_2d<f32>;
@group(1) @binding(2) var copy_depth:texture_depth_2d;
fn linear_depth(d:f32)->f32 {return 0.25*1000./(1000.-d*(1000.-0.25));}
fn depth_at(uv:vec2f)->f32 {
    let size=vec2i(textureDimensions(copy_depth));
    return linear_depth(textureLoad(copy_depth,clamp(vec2i(uv*vec2f(size)),vec2i(0),size-1),0));
}
fn upsample_fog(uv:vec2f)->vec4f {
    let size=vec2i(textureDimensions(valley_fog));let position=uv*vec2f(size)-0.5;
    let base=vec2i(floor(position));let fraction=position-vec2f(base);
    let center_depth=depth_at(uv);var sum=vec4f(0.);var total=0.;
    for(var i=0;i<4;i++) {
        let offset=vec2i(i&1,i>>1);let texel=clamp(base+offset,vec2i(0),size-1);
        let bilinear=select(1.-fraction.x,fraction.x,offset.x==1)*select(1.-fraction.y,fraction.y,offset.y==1);
        let sample_depth=depth_at((vec2f(texel)+0.5)/vec2f(size));
        let weight=(bilinear+0.001)/(0.02+abs(sample_depth-center_depth)/center_depth);
        sum+=textureLoad(valley_fog,texel,0)*weight;total+=weight;
    }
    return sum/total;
}
@fragment fn fs(v:Fullscreen)->@location(0) vec4f {
    let raw=textureLoad(raw_hdr,vec2i(v.position.xy),0).rgb;
    let fog=upsample_fog(v.position.xy/params.viewport.xy);
    return vec4f(max(raw*fog.a+fog.rgb,vec3f(0.)),1.);
}
