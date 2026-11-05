use glam::{Mat4, Vec3};
pub const WIDTH: u32 = 1280;
pub const HEIGHT: u32 = 720;
pub const WATER_LEVEL: f32 = 2.35;
pub const FRAME_STRIDE: u32 = 512;
pub fn srgb_to_linear(v: f32) -> f32 {
    if v <= 0.04045 {
        v / 12.92
    } else {
        ((v + 0.055) / 1.055).powf(2.4)
    }
}
pub fn camera(seconds: f64) -> (Vec3, Mat4) {
    let (eye, target) = camera_pose(seconds);
    (
        eye,
        projection() * glam::camera::rh::view::look_at_mat4(eye, target, Vec3::Y),
    )
}
pub fn projection() -> Mat4 {
    glam::camera::rh::proj::directx::perspective(
        84_f32.to_radians(),
        WIDTH as f32 / HEIGHT as f32,
        0.25,
        1000.,
    )
}
pub fn camera_pose(seconds: f64) -> (Vec3, Vec3) {
    let wave = (seconds.rem_euclid(60.) / 60. * std::f64::consts::TAU).sin() as f32;
    let yaw = wave * 0.06;
    let eye = Vec3::new(
        0.5 + yaw.sin() * 13.5,
        90.5 + wave * 0.25,
        0.5 + yaw.cos() * 13.5,
    );
    let target = eye
        + Vec3::new(
            -yaw.sin() * 100.,
            100. * (-16_f64).to_radians().tan() as f32,
            -yaw.cos() * 100.,
        );
    (eye, target)
}
pub fn reflected(point: Vec3) -> Vec3 {
    Vec3::new(point.x, 2. * WATER_LEVEL - point.y, point.z)
}
pub fn to_sun() -> Vec3 {
    let a = 72_f64.to_radians();
    let e = 20_f64.to_radians();
    Vec3::new(
        (a.sin() * e.cos()) as f32,
        e.sin() as f32,
        (-a.cos() * e.cos()) as f32,
    )
}
#[repr(C)]
#[derive(Clone, Copy, bytemuck::Pod, bytemuck::Zeroable)]
pub struct Params {
    pub vp: [[f32; 4]; 4],
    pub inverse_vp: [[f32; 4]; 4],
    pub eye: [f32; 4],
    pub sun: [f32; 4],
    pub shadow_vp: [[f32; 4]; 4],
    pub lighting: [f32; 4],
    pub viewport: [f32; 4],
    pub reflection_vp: [[f32; 4]; 4],
    pub cloud_vp: [[f32; 4]; 4],
    pub cloud: [f32; 4],
    pub animation: [f32; 4],
}
impl Params {
    pub fn at(t: f64) -> Self {
        Self::for_pass(t, false)
    }
    pub fn for_pass(t: f64, reflection: bool) -> Self {
        let (main_eye, main_target) = camera_pose(t);
        let coordinates = super::clouds::coordinates(t, main_eye);
        let reflection_view = glam::camera::rh::view::look_at_mat4(
            reflected(main_eye),
            reflected(main_target),
            -Vec3::Y,
        );
        let reflection_vp = projection() * reflection_view;
        let (eye, mut view) = if reflection {
            (reflected(main_eye), reflection_view)
        } else {
            (
                main_eye,
                glam::camera::rh::view::look_at_mat4(main_eye, main_target, Vec3::Y),
            )
        };
        let vp = projection() * view;
        view.w_axis = glam::Vec4::W;
        Self {
            vp: vp.to_cols_array_2d(),
            inverse_vp: vp.inverse().to_cols_array_2d(),
            eye: eye.extend(1.).to_array(),
            sun: to_sun().extend(0.).to_array(),
            shadow_vp: Mat4::IDENTITY.to_cols_array_2d(),
            lighting: [t.rem_euclid(31.41592653589793) as f32, 1.0, 0.0, 0.0],
            viewport: [
                if reflection { 640. } else { 1280. },
                if reflection { 360. } else { 720. },
                reflection as u8 as f32,
                0.,
            ],
            reflection_vp: reflection_vp.to_cols_array_2d(),
            cloud_vp: (projection() * view).to_cols_array_2d(),
            cloud: [
                coordinates.fraction[0],
                coordinates.fraction[2],
                coordinates.fraction[1] + if reflection { main_eye.y - eye.y } else { 0. },
                0.,
            ],
            animation: [
                t.rem_euclid(31.41592653589793) as f32,
                (t * 0.0495).rem_euclid(20.) as f32,
                0.,
                0.,
            ],
        }
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn projection_and_camera() {
        let (e, m) = camera(0.);
        assert_eq!(e, Vec3::new(0.5, 90.5, 14.));
        assert!((camera(60.).0 - e).length() < 1e-5);
        for z in [0.25, 1000.] {
            let p = glam::camera::rh::proj::directx::perspective(
                84_f32.to_radians(),
                16. / 9.,
                0.25,
                1000.,
            ) * glam::Vec4::new(0., 0., -z, 1.);
            assert!((p.z / p.w - if z < 1. { 0. } else { 1. }).abs() < 1e-5);
        }
        assert!(m.is_finite());
    }
}
