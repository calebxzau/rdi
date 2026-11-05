@group(1) @binding(0) var skins:texture_2d_array<f32>;
@group(1) @binding(1) var nearest_sampler:sampler;
const PLAYER_SCALE:f32=1.8/32.;
struct CrowdOut { @builtin(position) clip:vec4f, @location(0) uv:vec2f, @location(1) normal:vec3f, @location(2) @interpolate(flat) layer:u32, @location(3) world:vec3f }
fn rotate_x(v:vec3f,angle:f32)->vec3f {let c=cos(angle);let s=sin(angle);return vec3f(v.x,c*v.y-s*v.z,s*v.y+c*v.z);}
fn rotate_y(v:vec3f,angle:f32)->vec3f {let c=cos(angle);let s=sin(angle);return vec3f(c*v.x+s*v.z,v.y,c*v.z-s*v.x);}
// Mesh parts 2..5 are left arm, right arm, left leg, right leg; each limb swings about its own pivot.
@vertex fn vs(@location(0) position:vec3f,@location(1) uv:vec2f,@location(2) normal:vec3f,@location(3) part:f32,@location(5) origin:vec3f,@location(6) yaw:f32,@location(7) stride:f32,@location(8) arm:f32,@location(9) layer:u32)->CrowdOut {
    let id=u32(part+0.5);
    var local=position;
    var direction=normal;
    if id>=2u && id<=5u {
        let is_arm=id<=3u;
        let side=select(1.,-1.,id==2u||id==4u);
        let pivot=vec3f(side*select(1.9,arm,is_arm),select(12.,22.,is_arm),0.);
        let pitch=select(-stride,stride,id==2u||id==5u);
        local=rotate_x(local-pivot,pitch)+pivot;
        direction=rotate_x(direction,pitch);
    }
    let world=origin+rotate_y(local*PLAYER_SCALE,yaw);
    return CrowdOut(params.vp*vec4f(world,1.),uv,rotate_y(direction,yaw),layer,world);
}
@fragment fn fs(v:CrowdOut)->@location(0) vec4f {
    if params.viewport.z>0.5 && v.world.y<2.36 {discard;}
    let texel=textureSampleLevel(skins,nearest_sampler,v.uv/64.,v.layer,0.);
    if texel.a<0.05 {discard;}
    let normal=normalize(v.normal);
    let wrapped=clamp((dot(normal,params.sun.xyz)+0.4)/1.4,0.,1.);
    let sky_light=0.78+0.22*normal.y;
    let light=vec3f(0.33,0.40,0.56)*sky_light*1.35+vec3f(1.70,1.18,0.66)*wrapped;
    return vec4f(texel.rgb*light,texel.a);
}
