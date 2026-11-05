@group(1) @binding(0) var linear_hdr:texture_2d<f32>;
@group(1) @binding(1) var linear_sampler:sampler;
const WEIGHTS=array<f32,7>(1.,6.,15.,20.,15.,6.,1.);
fn bloom_tile(lod:f32,offset:vec2f,scaled_coord:vec2f)->vec3f {
    let scale=exp2(lod);let view=vec2f(textureDimensions(linear_hdr));
    let coord=(scaled_coord-offset)*scale;let padding=0.5+0.005*scale;
    var bloom=vec3f(0.);
    if abs(coord.x-0.5)<padding && abs(coord.y-0.5)<padding {
        for(var i=-3;i<=3;i++) {for(var j=-3;j<=3;j++) {
            let weight=WEIGHTS[u32(i+3)]*WEIGHTS[u32(j+3)];
            let sample_coord=(scaled_coord-offset+vec2f(f32(i),f32(j))/view)*scale;
            bloom+=textureSampleLevel(linear_hdr,linear_sampler,vec2f(sample_coord.x,1.-sample_coord.y),lod).rgb*weight;
        }}
        bloom/=4096.;
    }
    return pow(max(bloom/128.,vec3f(0.)),vec3f(0.25));
}
@fragment fn fs(v:Fullscreen)->@location(0) vec4f {
    // Keep the GL atlas layout in bottom-left coordinates; texture reads convert explicitly.
    let coord=vec2f(v.position.x/params.viewport.x,1.-v.position.y/params.viewport.y);
    var blur=bloom_tile(2.,vec2f(0.,0.),coord);
    blur+=bloom_tile(3.,vec2f(0.,0.26),coord);
    blur+=bloom_tile(4.,vec2f(0.135,0.26),coord);
    blur+=bloom_tile(5.,vec2f(0.2075,0.26),coord)*0.8;
    blur+=bloom_tile(6.,vec2f(0.135,0.3325),coord)*0.8;
    blur+=bloom_tile(7.,vec2f(0.160625,0.3325),coord)*0.6;
    blur+=bloom_tile(8.,vec2f(0.1784375,0.3325),coord)*0.4;
    return vec4f(blur,1.);
}
