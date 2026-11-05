//! Needed subset of PlayerModelCore.kt, with its exact face/UV/part order.
use glam::{Mat4, Vec3};
#[repr(C)]
#[derive(Clone, Copy, Debug, bytemuck::Pod, bytemuck::Zeroable)]
pub struct Vertex {
    pub position: [f32; 3],
    pub uv: [f32; 2],
    pub normal: [f32; 3],
    pub part: f32,
    pub shade: f32,
    pub texture: f32,
}
#[derive(Clone, Copy)]
struct Uv {
    u: f32,
    v: f32,
    w: f32,
    h: f32,
    flip: bool,
}
fn uv(u: i32, v: i32, w: i32, h: i32, d: i32) -> [Uv; 6] {
    let rect = |u, v, w, h, flip| Uv {
        u: u as f32,
        v: v as f32,
        w: w as f32,
        h: h as f32,
        flip,
    };
    [
        rect(u + d, v, w, d, false),
        rect(u + w + d, v, w, d, false),
        rect(u, v + d, d, h, false),
        rect(u + d, v + d, w, h, false),
        rect(u + w + d, v + d, d, h, false),
        rect(u + w + d * 2, v + d, w, h, true),
    ]
}
pub fn mesh(slim: bool, legacy: bool, outer: bool, cape: bool) -> Vec<Vertex> {
    let mut out = Vec::new();
    let width = if slim { 3 } else { 4 };
    let pivot = if slim { 5.5 } else { 6. };
    let mut cuboid =
        |center: [f32; 3], size: [f32; 3], uv: [Uv; 6], part: u32, inflate: f32, texture: f32| {
            let [l, d, b] = std::array::from_fn(|i| center[i] - (size[i] * 0.5 + inflate));
            let [r, u, f] = std::array::from_fn(|i| center[i] + (size[i] * 0.5 + inflate));
            let faces = [
                (
                    [[l, d, f], [r, d, f], [r, u, f], [l, u, f]],
                    [0., 0., 1.],
                    uv[3],
                    1.,
                    false,
                ),
                (
                    [[r, d, b], [l, d, b], [l, u, b], [r, u, b]],
                    [0., 0., -1.],
                    uv[5],
                    0.82,
                    false,
                ),
                (
                    [[l, d, b], [l, d, f], [l, u, f], [l, u, b]],
                    [-1., 0., 0.],
                    uv[2],
                    0.9,
                    false,
                ),
                (
                    [[r, d, f], [r, d, b], [r, u, b], [r, u, f]],
                    [1., 0., 0.],
                    uv[4],
                    0.78,
                    false,
                ),
                (
                    [[l, u, b], [l, u, f], [r, u, f], [r, u, b]],
                    [0., 1., 0.],
                    uv[0],
                    1.08,
                    true,
                ),
                (
                    [[l, d, f], [l, d, b], [r, d, b], [r, d, f]],
                    [0., -1., 0.],
                    uv[1],
                    0.62,
                    true,
                ),
            ];
            for (positions, normal, uv, shade, reverse) in faces {
                let (u0, u1) = if uv.flip {
                    (uv.u + uv.w, uv.u)
                } else {
                    (uv.u, uv.u + uv.w)
                };
                let coords = [[u0, uv.v + uv.h], [u1, uv.v + uv.h], [u1, uv.v], [u0, uv.v]];
                let order = if reverse { [0, 3, 2, 1] } else { [0, 1, 2, 3] };
                for i in [0, 1, 2, 0, 2, 3] {
                    out.push(Vertex {
                        position: positions[i],
                        uv: coords[order[i]],
                        normal,
                        part: part as f32,
                        shade,
                        texture,
                    });
                }
            }
        };
    cuboid([0., 28., 0.], [8., 8., 8.], uv(0, 0, 8, 8, 8), 0, 0., 0.);
    cuboid(
        [0., 18., 0.],
        [8., 12., 4.],
        uv(16, 16, 8, 12, 4),
        1,
        0.,
        0.,
    );
    cuboid(
        [-pivot, 18., 0.],
        [width as f32, 12., 4.],
        uv(40, 16, width, 12, 4),
        2,
        0.,
        0.,
    );
    cuboid(
        [pivot, 18., 0.],
        [width as f32, 12., 4.],
        if legacy {
            uv(40, 16, width, 12, 4)
        } else {
            uv(32, 48, width, 12, 4)
        },
        3,
        0.,
        0.,
    );
    cuboid(
        [-1.9, 6., -0.1],
        [4., 12., 4.],
        uv(0, 16, 4, 12, 4),
        4,
        0.,
        0.,
    );
    cuboid(
        [1.9, 6., -0.1],
        [4., 12., 4.],
        if legacy {
            uv(0, 16, 4, 12, 4)
        } else {
            uv(16, 48, 4, 12, 4)
        },
        5,
        0.,
        0.,
    );
    if outer {
        cuboid([0., 28., 0.], [8., 8., 8.], uv(32, 0, 8, 8, 8), 0, 0.5, 0.);
        if !legacy {
            cuboid(
                [0., 18., 0.],
                [8., 12., 4.],
                uv(16, 32, 8, 12, 4),
                1,
                0.25,
                0.,
            );
            cuboid(
                [-pivot, 18., 0.],
                [width as f32, 12., 4.],
                uv(40, 32, width, 12, 4),
                2,
                0.25,
                0.,
            );
            cuboid(
                [pivot, 18., 0.],
                [width as f32, 12., 4.],
                uv(48, 48, width, 12, 4),
                3,
                0.25,
                0.,
            );
            cuboid(
                [-1.9, 6., -0.1],
                [4., 12., 4.],
                uv(0, 32, 4, 12, 4),
                4,
                0.25,
                0.,
            );
            cuboid(
                [1.9, 6., -0.1],
                [4., 12., 4.],
                uv(0, 48, 4, 12, 4),
                5,
                0.25,
                0.,
            );
        }
    }
    if cape {
        cuboid(
            [0., 16., -2.6],
            [10., 16., 1.],
            uv(0, 0, 10, 16, 1),
            6,
            0.,
            1.,
        );
    }
    out
}
#[derive(Debug, Clone, Copy)]
pub struct Pose {
    pub head_yaw: f32,
    pub arm_roll: f32,
    pub arm_pitch: f32,
    pub cape_pitch: f32,
}
pub fn pose(seconds: f64) -> Pose {
    let ticks = seconds * 20.;
    Pose {
        head_yaw: ((seconds * std::f64::consts::TAU / 16.).sin() * 0.30) as f32,
        arm_roll: ((ticks * 0.09).cos() * 0.05 + 0.05) as f32,
        arm_pitch: ((ticks * 0.067).sin() * 0.05) as f32,
        cape_pitch: (0.12 + (seconds * std::f64::consts::TAU / 5.).sin() * 0.05) as f32,
    }
}
fn pivot(position: Vec3, pitch: f32, yaw: f32, roll: f32) -> Mat4 {
    Mat4::from_translation(position)
        * Mat4::from_rotation_y(yaw)
        * Mat4::from_rotation_x(pitch)
        * Mat4::from_rotation_z(roll)
        * Mat4::from_translation(-position)
}
#[repr(C)]
#[derive(Clone, Copy, bytemuck::Pod, bytemuck::Zeroable)]
pub struct Params {
    pub model: [[f32; 4]; 4],
    pub bones: [[[f32; 4]; 4]; 7],
    pub traits: [f32; 4],
}
impl Params {
    pub fn at(seconds: f64, slim: bool, cape: bool) -> Self {
        let p = pose(seconds);
        let arm = if slim { 5.5 } else { 6. };
        let bones = [
            pivot(Vec3::new(0., 24., 0.), 0., p.head_yaw, 0.),
            Mat4::IDENTITY,
            pivot(Vec3::new(-arm, 22., 0.), p.arm_pitch, 0., -p.arm_roll),
            pivot(Vec3::new(arm, 22., 0.), -p.arm_pitch, 0., p.arm_roll),
            Mat4::IDENTITY,
            Mat4::IDENTITY,
            pivot(Vec3::new(0., 24., -2.1), p.cape_pitch, 0., 0.),
        ];
        Self {
            model: (Mat4::from_translation(Vec3::new(0.5, 84., 0.5))
                * Mat4::from_rotation_y(std::f32::consts::PI)
                * Mat4::from_scale(Vec3::splat(1.8 / 32.)))
            .to_cols_array_2d(),
            bones: bones.map(|m| m.to_cols_array_2d()),
            traits: [cape as u8 as f32, 0., 0., 0.],
        }
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn variants_winding_and_uv_contract() {
        assert_eq!(std::mem::size_of::<Vertex>(), 44);
        for slim in [false, true] {
            for legacy in [false, true] {
                let mesh = mesh(slim, legacy, true, true);
                assert_eq!(mesh.len(), if legacy { 288 } else { 468 });
                assert_eq!(mesh.iter().filter(|v| v.texture == 1.).count(), 36);
                for t in mesh.chunks_exact(3) {
                    let a = Vec3::from(t[0].position);
                    let b = Vec3::from(t[1].position);
                    let c = Vec3::from(t[2].position);
                    assert!((b - a).cross(c - a).dot(Vec3::from(t[0].normal)) > 0.001);
                    assert!(
                        t.iter()
                            .all(|v| v.uv.iter().all(|&uv| (0.0..=64.0).contains(&uv)))
                    );
                }
                let head_top: Vec<_> = mesh
                    .iter()
                    .filter(|v| v.part == 0. && v.normal[1] == 1.)
                    .map(|v| v.uv)
                    .collect();
                for uv in [
                    [8., 0.],
                    [16., 0.],
                    [8., 8.],
                    [16., 8.],
                    [40., 0.],
                    [48., 8.],
                ] {
                    assert!(head_top.contains(&uv));
                }
            }
        }
    }
    #[test]
    fn idle_pose_bounds_periods_and_bone_pivots() {
        assert_eq!(pose(0.).head_yaw, 0.);
        assert_eq!(pose(0.).arm_roll, 0.1);
        assert!((pose(4.).head_yaw - 0.3).abs() < 1e-6);
        assert!((pose(12.).head_yaw + 0.3).abs() < 1e-6);
        for t in [0., 15., 45., 1e9] {
            let p = pose(t);
            assert!(p.head_yaw.abs() <= 0.3 && p.arm_pitch.abs() <= 0.05);
            assert!((0.0..=0.1).contains(&p.arm_roll));
            assert!((0.07..=0.17).contains(&p.cape_pitch));
            for slim in [false, true] {
                let p = Params::at(t, slim, true);
                let arm = if slim { 5.5 } else { 6. };
                for (i, pivot) in [
                    (0, Vec3::new(0., 24., 0.)),
                    (2, Vec3::new(-arm, 22., 0.)),
                    (3, Vec3::new(arm, 22., 0.)),
                    (6, Vec3::new(0., 24., -2.1)),
                ] {
                    assert!(
                        (Mat4::from_cols_array_2d(&p.bones[i]).transform_point3(pivot) - pivot)
                            .length()
                            < 1e-4
                    );
                }
                assert!(
                    (Mat4::from_cols_array_2d(&p.model).transform_point3(Vec3::ZERO)
                        - Vec3::new(0.5, 84., 0.5))
                    .length()
                        < 1e-5
                );
            }
        }
    }
}
