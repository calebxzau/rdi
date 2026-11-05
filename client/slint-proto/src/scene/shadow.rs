use super::{state, surface::Aabb, terrain::Grid};
use glam::{Mat4, Vec3};
#[derive(Clone, Copy)]
pub struct Frame {
    pub vp: Mat4,
    pub depth_range: f32,
    pub bounds: Aabb,
}
pub fn frame(g: &Grid) -> Frame {
    let mut low = i32::MAX;
    let mut high = i32::MIN;
    for x in (-172..320).step_by(4) {
        for z in (-240..32).step_by(4) {
            let h = g.at(x, z);
            low = low.min(h);
            high = high.max(h);
        }
    }
    let bounds = Aabb::new(
        [-172.0, (low - 2) as f32, -240.0],
        [320.0, (high + 2) as f32, 32.0],
    );
    let center = (Vec3::from_array(bounds.min) + Vec3::from_array(bounds.max)) * 0.5;
    let eye = center + state::to_sun() * 400.0;
    let view = glam::camera::rh::view::look_at_mat4(eye, center, Vec3::Z);
    let mut min = Vec3::splat(f32::INFINITY);
    let mut max = Vec3::splat(f32::NEG_INFINITY);
    for p in bounds.corners() {
        let p = view.transform_point3(Vec3::from_array(p));
        min = min.min(p);
        max = max.max(p);
    }
    let near = 0.1_f32.max(-max.z - 8.0);
    let far = (near + 1.0).max(-min.z + 8.0);
    let projection = glam::camera::rh::proj::directx::orthographic(
        min.x - 4.0,
        max.x + 4.0,
        min.y - 4.0,
        max.y + 4.0,
        near,
        far,
    );
    Frame {
        vp: projection * view,
        depth_range: far - near,
        bounds,
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn shadow_frame_contains_bounds_in_wgpu_depth() {
        let f = frame(&Grid::new());
        for p in f.bounds.corners() {
            let p = f.vp * Vec3::from_array(p).extend(1.0);
            assert!(p.x.abs() <= 1.0 && p.y.abs() <= 1.0 && (0.0..=1.0).contains(&p.z));
        }
        assert!(f.depth_range > 1.0);
    }
}
