// Cover-crops the background image into the frame (ContentScale.Crop).

struct Params {
    content_transform: vec4f,
}

@group(0) @binding(0) var<uniform> params: Params;

@fragment
fn fs(@builtin(position) pos: vec4f) -> @location(0) vec4f {
    return sample_content(pos.xy, params.content_transform);
}
