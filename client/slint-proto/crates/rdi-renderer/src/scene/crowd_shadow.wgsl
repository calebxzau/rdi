// Soft contact shadow that stays inside the runner's supporting block; main pass only.
struct ShadowOut { @builtin(position) clip:vec4f, @location(0) local:vec2f }
@vertex fn vs(@builtin(vertex_index) index:u32,@location(0) position:vec3f)->ShadowOut {
    var corners=array<vec2f,6>(vec2f(-1.,-1.),vec2f(1.,-1.),vec2f(1.,1.),vec2f(-1.,-1.),vec2f(1.,1.),vec2f(-1.,1.));
    let local=corners[index];
    return ShadowOut(params.vp*vec4f(position+vec3f(local.x*0.4,0.012,local.y*0.4),1.),local);
}
@fragment fn fs(v:ShadowOut)->@location(0) vec4f {
    let alpha=(1.-smoothstep(0.15,1.,length(v.local)))*0.24;
    return vec4f(0.025,0.035,0.015,alpha);
}
