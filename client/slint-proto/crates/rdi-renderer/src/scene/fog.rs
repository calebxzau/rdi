//! CPU mirrors of ValleyFog.kt. The upload owns no retained copy of the noise volume.
pub const SIZE: u32 = 64;
pub const BOTTOM: f32 = 2.;
pub const TOP: f32 = 10.;
pub const SOFTNESS: f32 = 3.;
pub const MAX_RISE: f32 = 14.;
fn smoothstep(a: f32, b: f32, x: f32) -> f32 {
    let t = ((x - a) / (b - a)).clamp(0., 1.);
    t * t * (3. - 2. * t)
}
pub fn top(z: f32) -> f32 {
    TOP + ((-160. - z) * 0.05).clamp(0., MAX_RISE)
}
pub fn layer(y: f32, z: f32) -> f32 {
    smoothstep(BOTTOM - SOFTNESS, BOTTOM + SOFTNESS, y)
        * (1. - smoothstep(top(z) - SOFTNESS, top(z) + SOFTNESS, y))
}
pub fn reach(z: f32) -> f32 {
    1. - smoothstep(-120., -50., z)
}
pub fn slab(eye_y: f32, direction_y: f32, distance: f32) -> Option<(f32, f32)> {
    let low = BOTTOM - SOFTNESS;
    let high = TOP + MAX_RISE + SOFTNESS;
    let (start, end) = if direction_y.abs() < 1e-5 {
        if !(low..=high).contains(&eye_y) {
            return None;
        }
        (0., distance)
    } else {
        let a = (low - eye_y) / direction_y;
        let b = (high - eye_y) / direction_y;
        (a.min(b).max(0.), a.max(b).min(distance))
    };
    (end > start).then_some((start, end))
}
pub fn steps(length: f32) -> u32 {
    (length / 6.).ceil().clamp(6., 24.) as u32
}
#[repr(C)]
#[derive(Clone, Copy, bytemuck::Pod, bytemuck::Zeroable)]
pub struct Wind {
    pub coarse: [f32; 4],
    pub detail: [f32; 4],
}
impl Wind {
    pub fn at(seconds: f64) -> Self {
        let wrap = |v: f64| (v - v.floor()) as f32;
        let detail = seconds * 1.7;
        Self {
            coarse: [wrap(seconds * 0.0009), 0., wrap(seconds * 0.0004), 0.],
            detail: [wrap(-detail * 0.0009), 0., wrap(detail * 0.0004 * 0.5), 0.],
        }
    }
}
/// Tileable four-octave value noise, x fastest, with Kotlin's f32 operation order.
pub fn noise(size: u32, seed: i32) -> Vec<u8> {
    let mut values = vec![0_f32; size.pow(3) as usize];
    let mut amplitude = 0.5;
    let mut periods = 4_i32;
    for octave in 0..4_i32 {
        let lattice: Vec<_> = (0..periods.pow(3))
            .map(|i| {
                let x = i % periods;
                let y = (i / periods) % periods;
                let z = i / (periods * periods);
                super::terrain::hash01(x, z, (seed + octave * 7907) ^ y.wrapping_mul(0x045D_9F3B))
                    as f32
            })
            .collect();
        let at = |x: i32, y: i32, z: i32| {
            lattice[((z.rem_euclid(periods) * periods + y.rem_euclid(periods)) * periods
                + x.rem_euclid(periods)) as usize]
        };
        let fade = |t: f32| t * t * t * (t * (t * 6. - 15.) + 10.);
        let lerp = |a: f32, b: f32, t: f32| a + (b - a) * t;
        let scale = periods as f32 / size as f32;
        for z in 0..size {
            for y in 0..size {
                for x in 0..size {
                    let p = [x, y, z].map(|v| (v as f32 + 0.5) * scale);
                    let [ix, iy, iz] = p.map(|v| v.floor() as i32);
                    let [fx, fy, fz] = p.map(|v| fade(v - v.floor()));
                    let plane = |z| {
                        lerp(
                            lerp(at(ix, iy, z), at(ix + 1, iy, z), fx),
                            lerp(at(ix, iy + 1, z), at(ix + 1, iy + 1, z), fx),
                            fy,
                        )
                    };
                    values[((z * size + y) * size + x) as usize] +=
                        lerp(plane(iz), plane(iz + 1), fz) * amplitude;
                }
            }
        }
        amplitude *= 0.5;
        periods *= 2;
    }
    let low = values.iter().copied().fold(f32::INFINITY, f32::min);
    let high = values.iter().copied().fold(f32::NEG_INFINITY, f32::max);
    values
        .into_iter()
        .map(|v| ((v - low) / (high - low) * 255. + 0.5) as u8)
        .collect()
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn valley_layer_and_reach_keep_summit_and_sky_clear() {
        assert_eq!(layer(-1., -120.), 0.);
        assert_eq!(layer(13., -120.), 0.);
        assert_eq!(layer(6., -120.), 1.);
        assert!(layer(super::super::state::WATER_LEVEL, -120.) > 0.3);
        assert_eq!(layer(84., 0.5), 0.);
        assert_eq!(reach(0.5), 0.);
        assert_eq!(reach(-50.), 0.);
        assert_eq!(reach(-120.), 1.);
        assert!((0.3..0.7).contains(&reach(-85.)));
        assert!(top(-400.) > top(-250.) && top(-250.) > TOP);
        assert_eq!(top(-2000.), TOP + MAX_RISE);
        assert!(TOP + MAX_RISE + SOFTNESS < super::super::state::camera(0.).0.y - 1.);
    }
    #[test]
    fn slab_clipping_and_step_limits() {
        let down = -10_f32.to_radians().sin();
        let (a, b) = slab(30.5, down, 10000.).unwrap();
        assert!((a - (27. - 30.5) / down).abs() < 1e-3);
        assert!((b - (-1. - 30.5) / down).abs() < 1e-3);
        assert_eq!(slab(30.5, down, 80.).unwrap().1, 80.);
        assert_eq!(slab(30.5, 0.2, 10000.), None);
        assert_eq!(slab(30.5, down, a - 1.), None);
        assert_eq!(slab(8., 0., 300.), Some((0., 300.)));
        assert_eq!(slab(30.5, 0., 300.), None);
        assert_eq!(slab(8., 0.5, 10000.), Some((0., 38.)));
        assert_eq!([steps(1.), steps(60.), steps(5000.)], [6, 10, 24]);
    }
    #[test]
    fn wind_wrap_and_continuity() {
        for t in [0., 1., 3600., 1e9] {
            let wind = Wind::at(t);
            assert!(
                wind.coarse
                    .into_iter()
                    .chain(wind.detail)
                    .all(|v| (0.0..1.0).contains(&v))
            );
        }
        let a = Wind::at(500.);
        let b = Wind::at(500.1);
        for (a, b) in a
            .coarse
            .into_iter()
            .chain(a.detail)
            .zip(b.coarse.into_iter().chain(b.detail))
        {
            let d = (a - b).abs();
            assert!(!(0.001..=0.999).contains(&d));
        }
        assert!((Wind::at(10.).coarse[0] - Wind::at(10. + 1. / 0.0009).coarse[0]).abs() < 1e-4);
    }
    #[test]
    fn noise_is_deterministic_full_range_and_seamless() {
        let data = noise(SIZE, 0x0F06);
        assert_eq!(data, noise(SIZE, 0x0F06));
        assert_eq!(data.len(), SIZE.pow(3) as usize);
        assert_eq!(data.iter().min(), Some(&0));
        assert_eq!(data.iter().max(), Some(&255));
        let mean = data.iter().map(|&v| v as f64).sum::<f64>() / data.len() as f64;
        assert!((90.0..165.0).contains(&mean));
        let at = |x: u32, y: u32, z: u32| data[((z * SIZE + y) * SIZE + x) as usize] as f64;
        let mut inside = 0.;
        let mut seam = 0.;
        let mut samples = 0.;
        for a in (0..SIZE).step_by(3) {
            for b in (0..SIZE).step_by(3) {
                inside += (at(31, a, b) - at(32, a, b)).abs()
                    + (at(a, 31, b) - at(a, 32, b)).abs()
                    + (at(a, b, 31) - at(a, b, 32)).abs();
                seam += (at(63, a, b) - at(0, a, b)).abs()
                    + (at(a, 63, b) - at(a, 0, b)).abs()
                    + (at(a, b, 63) - at(a, b, 0)).abs();
                samples += 1.;
            }
        }
        assert!(seam / samples < inside / samples * 1.5 + 1.);
    }
}
