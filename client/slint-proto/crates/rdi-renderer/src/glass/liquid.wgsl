// Ported from LIQUID_GLASS_SKSL_FUNCTIONS (client/ui LiquidGlass.kt). Straight colour uniforms,
// premultiplied output.

fn sd_rounded_rect(p: vec2f, half_size: vec2f, r: f32) -> f32 {
    let q = abs(p) - (half_size - r);
    return length(max(q, vec2f(0.0))) + min(max(q.x, q.y), 0.0) - r;
}

fn outward_normal(p: vec2f, half_size: vec2f, r: f32) -> vec2f {
    let q = abs(p) - (half_size - r);
    var n: vec2f;
    if (q.x > 0.0 && q.y > 0.0) {
        n = normalize(q);
    } else if (q.x > q.y) {
        n = vec2f(1.0, 0.0);
    } else {
        n = vec2f(0.0, 1.0);
    }
    return vec2f(select(n.x, -n.x, p.x < 0.0), select(n.y, -n.y, p.y < 0.0));
}

// 斜面光学：xy为朝中心的采样位移，z为镜面高光强度；dist为离边距离，n为外法线
fn bevel_optics(
    dist: f32, n: vec2f, bevel: f32, taper: f32, refractive_index: f32,
    light_direction: vec2f, specular_intensity: f32, shininess: f32,
) -> vec3f {
    if (dist >= bevel) {
        return vec3f(0.0);
    }
    let t = max(dist / bevel, 0.02);
    let u = 1.0 - t;
    let u3 = u * u * u;
    let slope = u3 / pow(1.0 - u3 * u, 0.75);
    let theta1 = atan(slope);
    let theta2 = asin(clamp(sin(theta1) / refractive_index, -1.0, 1.0));
    let fade = smoothstep(0.0, 1.0, (bevel - dist) / taper) * smoothstep(0.0, 1.0, dist);
    let displacement = -n * (tan(theta1 - theta2) * bevel * fade);
    let sin_t = sin(theta1);
    let directional = pow(abs(dot(n * sin_t, light_direction)), shininess);
    let rim = max(directional, sin_t * 0.06) * specular_intensity * min(1.0, sin_t * 1.8);
    return vec3f(displacement, clamp(rim * fade, 0.0, 1.0));
}

// 发丝描边：离边hairline_width内的一圈
fn with_hairline(color: vec4f, dist: f32, hairline: vec4f, hairline_width: f32) -> vec4f {
    let hair = hairline.a * clamp(hairline_width + 0.5 - dist, 0.0, 1.0);
    return over(vec4f(hairline.rgb * hair, hair), color);
}
