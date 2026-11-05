struct Vertex { @location(0) position:vec3f,@location(1) normal:vec3f,@location(2) color:vec3f,@location(3) kind:f32,@location(4) uv:vec2f,@location(5) ao:f32 }
@vertex fn vs(v:Vertex)->Out {return emit_vertex(v.position,v.normal,v.color,v.kind,v.uv,v.ao,false);}
@vertex fn shadow_vs(v:Vertex)->Out {return emit_vertex(v.position,v.normal,v.color,v.kind,v.uv,v.ao,true);}
