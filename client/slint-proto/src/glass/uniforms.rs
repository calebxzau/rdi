/// Packs a WGSL uniform struct field by field with WGSL's alignment rules
/// (f32: 4, vec2f: 8, vec4f: 16; the struct size is rounded up to its largest alignment).
/// Fields must be pushed in the order the shader's `Params` struct declares them.
#[derive(Default)]
pub struct UniformWriter {
    bytes: Vec<u8>,
    offsets: Vec<u32>,
    align: usize,
}
impl UniformWriter {
    fn push(&mut self, align: usize, values: &[f32]) -> &mut Self {
        self.align = self.align.max(align);
        self.bytes
            .resize(self.bytes.len().next_multiple_of(align), 0);
        self.offsets.push(self.bytes.len() as u32);
        for v in values {
            self.bytes.extend_from_slice(&v.to_ne_bytes());
        }
        self
    }
    pub fn f32(&mut self, v: f32) -> &mut Self {
        self.push(4, &[v])
    }
    pub fn vec2(&mut self, v: [f32; 2]) -> &mut Self {
        self.push(8, &v)
    }
    pub fn vec4(&mut self, v: [f32; 4]) -> &mut Self {
        self.push(16, &v)
    }
    pub fn finish(&mut self) -> Vec<u8> {
        self.offsets.clear();
        let align = std::mem::take(&mut self.align).max(1);
        let mut bytes = std::mem::take(&mut self.bytes);
        bytes.resize(bytes.len().next_multiple_of(align), 0);
        bytes
    }
    #[cfg(test)]
    pub fn offsets(&self) -> &[u32] {
        &self.offsets
    }
}
#[derive(Debug)]
pub struct Uniforms {
    pub bevel: f32,
    pub taper: f32,
    pub radius: f32,
    pub frost: f32,
    pub light: [f32; 2],
    pub shininess: f32,
    pub shadow: [f32; 2],
    pub edge: f32,
}
pub fn tile(scale: f32) -> Uniforms {
    let bevel = (9.0 * scale).max(0.001);
    Uniforms {
        bevel,
        taper: (8.0 * scale).min(bevel * 0.45).max(0.001),
        radius: 22.0 * scale,
        frost: 8.0 * scale,
        light: [0.5, 3.0_f32.sqrt() / 2.0],
        shininess: 6.0,
        shadow: [10.0 * scale * 0.57735 + 0.5, 16.0 * scale * 0.57735 + 0.5],
        edge: 1.5 * scale * 0.57735 + 0.5,
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn kotlin_values() {
        for (s, b, t, r, f, a, h, e) in [
            (1.0, 9.0, 4.05, 22.0, 8.0, 6.2735, 9.7376, 1.366025),
            (1.5, 13.5, 6.075, 33.0, 12.0, 9.16025, 14.3564, 1.7990375),
        ] {
            let u = tile(s);
            for (v, w) in [
                (u.bevel, b),
                (u.taper, t),
                (u.radius, r),
                (u.frost, f),
                (u.shadow[0], a),
                (u.shadow[1], h),
                (u.edge, e),
                (u.shininess, 6.0),
                (u.light[0], 0.5),
                (u.light[1], 0.8660254),
            ] {
                assert!((v - w).abs() < 0.00001, "{v} != {w}");
            }
        }
    }
}
