//! Explicit generator-side quantization; the packed codec remains strict and never rounds.
use anyhow::{Result, ensure};
use glam::Vec3;
#[derive(Clone, Debug, Default, serde::Serialize)]
pub struct Displacement {
    pub vertices: u64,
    pub moved_vertices: u64,
    pub max_axis: [f32; 3],
    pub max_distance: f64,
    pub sum_axis: [f64; 3],
    pub sum_distance: f64,
}
impl Displacement {
    pub fn record(&mut self, original: [f32; 3], emitted: [f32; 3]) {
        self.vertices += 1;
        let delta = std::array::from_fn::<_, 3, _>(|i| (emitted[i] - original[i]).abs());
        if delta.iter().any(|&v| v != 0.0) {
            self.moved_vertices += 1;
        }
        for (i, &d) in delta.iter().enumerate() {
            self.max_axis[i] = self.max_axis[i].max(d);
            self.sum_axis[i] += d as f64;
        }
        let length = delta
            .iter()
            .map(|&v| (v as f64).powi(2))
            .sum::<f64>()
            .sqrt();
        self.max_distance = self.max_distance.max(length);
        self.sum_distance += length;
    }
    pub fn report(&self) -> serde_json::Value {
        serde_json::json!({"vertices":self.vertices,"moved_vertices":self.moved_vertices,"max_axis":self.max_axis,"mean_axis":self.sum_axis.map(|v|v/self.vertices.max(1) as f64),"max_distance":self.max_distance,"mean_distance":self.sum_distance/self.vertices.max(1) as f64,"mean_moved_distance":self.sum_distance/self.moved_vertices.max(1) as f64})
    }
}
pub fn scalar(p: f32) -> Result<f32> {
    ensure!(p.is_finite(), "non-finite generated coordinate");
    let q = (p * 16.0).round() / 16.0;
    ensure!(
        q.is_finite() && (q - p).abs() <= 1.0 / 32.0,
        "quantization exceeds1/32 block: {p} -> {q}"
    );
    Ok(q)
}
pub fn point(p: [f32; 3]) -> Result<[f32; 3]> {
    Ok([scalar(p[0])?, scalar(p[1])?, scalar(p[2])?])
}
pub fn offset(anchor: f32, detail: f32) -> Result<()> {
    let before = detail - anchor;
    ensure!(
        before.is_finite() && before != 0.0,
        "expected a non-zero source outset/inset"
    );
    let after = scalar(detail)? - scalar(anchor)?;
    ensure!(
        after != 0.0 && after.signum() == before.signum(),
        "outset/inset collapsed or reversed: {anchor} -> {detail} ({before} -> {after})"
    );
    Ok(())
}
pub fn extent(min: [f32; 3], max: [f32; 3]) -> Result<()> {
    for i in 0..3 {
        ensure!(max[i] > min[i], "invalid source part extent");
        offset(min[i], max[i])?;
    }
    Ok(())
}
pub fn face(original: [[f32; 3]; 4], emitted: [[f32; 3]; 4], flip: bool) -> Result<()> {
    let triangles = if flip {
        [[1, 2, 3], [1, 3, 0]]
    } else {
        [[0, 1, 2], [0, 2, 3]]
    };
    for [a, b, c] in triangles {
        let cross = |p: [[f32; 3]; 4]| {
            (Vec3::from_array(p[b]) - Vec3::from_array(p[a]))
                .cross(Vec3::from_array(p[c]) - Vec3::from_array(p[a]))
        };
        let before = cross(original);
        let after = cross(emitted);
        ensure!(
            before.length_squared() > 0.0 && after.length_squared() > 0.0,
            "face became zero-area during quantization"
        );
        ensure!(
            before.dot(after) > 0.0,
            "quantization reversed face winding"
        );
    }
    Ok(())
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn reject_collapsed_parts_offsets_and_faces() {
        assert!(offset(0.0, 0.01).is_err());
        assert!(offset(0.0, -0.01).is_err());
        assert!(extent([0.0; 3], [0.01, 1.0, 1.0]).is_err());
        for d in [0.06, -0.06, 0.1, -0.1] {
            offset(0.0, d).unwrap();
        }
        let p = [
            [0.0, 0.0, 0.0],
            [0.01, 0.0, 0.0],
            [0.01, 1.0, 0.0],
            [0.0, 1.0, 0.0],
        ];
        assert!(face(p, p.map(|p| point(p).unwrap()), false).is_err());
    }
    #[test]
    fn bound_and_sign_cover_nearby_world_planes() {
        for base in [-1000.0, -71.0, 0.0, 84.0, 1000.0] {
            for i in -400..400 {
                let p = base + i as f32 * 0.00037;
                assert!((scalar(p).unwrap() - p).abs() <= 1.0 / 32.0);
            }
        }
    }
}
