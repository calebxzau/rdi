struct BatchParams { origin:vec4f, traits:vec4f }
@group(1) @binding(0) var<uniform> batch:BatchParams;
struct Out {
 @builtin(position) clip:vec4f,@location(0) world:vec3f,@location(1) normal:vec3f,
 @location(2) color:vec3f,@location(3) ao:f32,@location(4) uv:vec2f,
 @location(5) shadow:vec4f,@location(6) @interpolate(flat) kind:f32
}
fn emit_vertex(original:vec3f,normal:vec3f,color:vec3f,kind:f32,uv:vec2f,ao:f32,shadow:bool)->Out {
 var p=original;
 if !shadow && kind>1.5 && kind<2.5 {
  // Preserve the reference frequencies, amplitudes, time wrap and phase formula.
  let phase=p.x*0.7+p.z*0.5;
  p.x+=sin(params.lighting.x*2.+phase)*0.07;
  p.z+=sin(params.lighting.x*3.+phase*1.3)*0.05;
 }
 let world=vec4f(p,1.);
 return Out(select(params.vp*world,params.shadow_vp*world,shadow),p,normal,color,ao,uv,params.shadow_vp*world,kind);
}
