struct Vertex { @location(0) position:vec4i,@location(1) normal:vec4f,@location(2) color_ao:vec4f,@location(3) uv:vec2f }
@vertex fn vs(v:Vertex)->Out {return emit_vertex(vec3f(v.position.xyz)/16.+batch.origin.xyz,v.normal.xyz,v.color_ao.rgb,f32(v.position.w),v.uv,v.color_ao.a,false);}
@vertex fn shadow_vs(v:Vertex)->Out {return emit_vertex(vec3f(v.position.xyz)/16.+batch.origin.xyz,v.normal.xyz,v.color_ao.rgb,f32(v.position.w),v.uv,v.color_ao.a,true);}
