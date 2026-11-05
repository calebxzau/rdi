//! Golden-valley terrain, ported from BackgroundTerrain.kt. Noise stays f64 and wraps at 32 bits.
use super::mesh::{Builder, Material as M, Mesh};
use std::sync::OnceLock;
const SEED: i32 = 0x5eed;
pub const FLOOR: i32 = -8;
#[derive(Clone, Copy, Debug)]
pub struct Band {
    pub min_x: i32,
    pub max_x: i32,
    pub min_z: i32,
    pub max_z: i32,
    pub size: i32,
}
impl Band {
    fn columns(self) -> usize {
        ((self.max_x - self.min_x) / self.size) as usize
    }
    fn rows(self) -> usize {
        ((self.max_z - self.min_z) / self.size) as usize
    }
    fn contains(self, x: i32, z: i32) -> bool {
        x >= self.min_x && x < self.max_x && z >= self.min_z && z < self.max_z
    }
}
pub const BANDS: [Band; 4] = [
    Band {
        min_x: -96,
        max_x: 96,
        min_z: -96,
        max_z: 40,
        size: 1,
    },
    Band {
        min_x: -224,
        max_x: 224,
        min_z: -224,
        max_z: 40,
        size: 2,
    },
    Band {
        min_x: -768,
        max_x: 768,
        min_z: -576,
        max_z: 40,
        size: 4,
    },
    Band {
        min_x: -1024,
        max_x: 1024,
        min_z: -1024,
        max_z: 40,
        size: 8,
    },
];
pub fn hash01(ix: i32, iz: i32, seed: i32) -> f64 {
    let mut h =
        seed as u32 ^ (ix as u32).wrapping_mul(0x27d4eb2d) ^ (iz as u32).wrapping_mul(0x165667b1);
    h = (h ^ (h >> 15)).wrapping_mul(0x2c1b3c6d);
    h = (h ^ (h >> 12)).wrapping_mul(0x297a2d39);
    h ^= h >> 15;
    (h & 0xffffff) as f64 / 16_777_215.0
}
pub fn value(x: f64, z: f64, seed: i32) -> f64 {
    let ix = x.floor() as i32;
    let iz = z.floor() as i32;
    let fx = x - x.floor();
    let fz = z - z.floor();
    let fade = |v: f64| v * v * v * (v * (v * 6.0 - 15.0) + 10.0);
    let u = fade(fx);
    let v = fade(fz);
    let a = hash01(ix, iz, seed);
    let b = hash01(ix + 1, iz, seed);
    let c = hash01(ix, iz + 1, seed);
    let d = hash01(ix + 1, iz + 1, seed);
    (a + (b - a) * u + (c - a) * v + (a - b - c + d) * u * v) * 2.0 - 1.0
}
fn noise(x: f64, z: f64, seed: i32, octaves: i32, ridged: bool) -> f64 {
    let (mut total, mut amplitude, mut frequency, mut norm, mut weight) = (0.0, 0.5, 1.0, 0.0, 1.0);
    for octave in 0..octaves {
        let mut n = value(
            x * frequency,
            z * frequency,
            seed + octave * if ridged { 7919 } else { 1013 },
        );
        if ridged {
            n = 1.0 - n.abs();
            n = n * n * weight;
            weight = (n * 1.6).clamp(0.0, 1.0);
        }
        total += n * amplitude;
        norm += amplitude;
        amplitude *= 0.5;
        frequency *= 2.0;
    }
    total / norm
}
pub(crate) fn fbm(x: f64, z: f64, seed: i32) -> f64 {
    noise(x, z, seed, 4, false)
}
fn smooth(a: f64, b: f64, x: f64) -> f64 {
    let t = ((x - a) / (b - a)).clamp(0.0, 1.0);
    t * t * (3.0 - 2.0 * t)
}
fn mountain(x: f64, z: f64) -> f64 {
    const PEAKS: [[f64; 5]; 8] = [
        [185., -385., 211., 156., 0.4],
        [105., -440., 177., 130., 2.1],
        [270., -425., 180., 136., 4.],
        [-380., -410., 195., 169., 1.3],
        [-210., -480., 167., 143., 3.2],
        [-70., -525., 174., 150., 5.1],
        [35., -505., 161., 130., 0.9],
        [410., -505., 164., 162., 2.7],
    ];
    let wx = x + fbm(x * 0.009, z * 0.009, SEED + 820) * 30.;
    let wz = z + fbm(x * 0.009, z * 0.009, SEED + 840) * 30.;
    let mut massif: f64 = 0.;
    for [px, pz, h, r, p] in PEAKS {
        let dx = wx - px;
        let dz = wz - pz;
        let angle = dz.atan2(dx);
        let spurs = 1.
            + 0.30 * (3. * angle + p).sin()
            + 0.16 * (5. * angle + p * 1.7).sin()
            + 0.12 * value(angle * 2. + p * 5., 0.5, SEED + 860);
        let d = (dx * dx + dz * dz).sqrt() / (r * spurs);
        massif = massif.max(h * (1. - d).clamp(0., 1.).powf(1.6));
    }
    let crags =
        (noise(x * 0.04, z * 0.04, SEED + 980, 3, true) - 0.5) * 34. * (massif / 90.).clamp(0., 1.);
    let foothills = 14. + 12. * fbm(x * 0.012, z * 0.012, SEED + 950);
    let rim =
        smooth(-460., -570., z) * (30. + 22. * noise(x * 0.015, z * 0.015, SEED + 900, 5, true));
    let raw = (massif + crags).max(foothills.max(rim));
    (if raw > 196. {
        196. + (raw - 196.) * 0.5
    } else {
        raw
    }) * smooth(-250., -360., z)
}
pub fn lake_distance(x: f64, z: f64) -> f64 {
    let lx = (x - 70.) / 125.;
    let lz = (z + 185.) / 40.;
    let lake = (lx * lx + lz * lz).sqrt() + fbm(x * 0.03, z * 0.03, SEED + 1500) * 0.26;
    let river = (z - (-176. + (x / 32.).sin() * 10.)).abs() / 8.
        + (1. - smooth(-260., -220., x)) * 3.
        + smooth(-70., -30., x) * 3.;
    lake.min(river)
}
pub(crate) fn base_height(x: f64, z: f64) -> f64 {
    let mut h = 7.
        + fbm(x * 0.02, z * 0.02, SEED) * 2.6
        + noise(x * 0.08, z * 0.08, SEED + 50, 2, false) * 0.8;
    let right = smooth(30., 80., x) * (1. - smooth(-80., -100., z));
    h += right * (2. + fbm(x * 0.03, z * 0.03, SEED + 200) * 3.5);
    let lake = lake_distance(x, z);
    let dip = 3.5 * (1. - smooth(1., 3., lake));
    if dip > 0. {
        h = (h - dip).max(h.min(2.35_f32 as f64 + 1.2));
    }
    for (cz, w, b, a, s) in [
        (-252., 18., 20., 10., SEED + 300),
        (-290., 22., 30., 14., SEED + 400),
    ] {
        let wobble = fbm(x * 0.01, 0., s + 7) * 18.;
        let band = (-((z - cz + wobble) / w).powi(2)).exp();
        h = h.max(
            band * smooth(-0.35, 0.15, fbm(x * 0.008, z * 0.008, s + 3))
                * (b + a * fbm(x * 0.018, z * 0.018, s)),
        );
    }
    h = h.max(mountain(x, z));
    let wx = (x + 88.) / 52.;
    let wz = (z + 88.) / 40.;
    let woods = 1.
        - smooth(
            0.,
            1.,
            (wx * wx + wz * wz).sqrt() + fbm(x * 0.03, z * 0.03, SEED + 1200) * 0.15,
        );
    h += 14. * woods * (0.85 + 0.3 * fbm(x * 0.04, z * 0.04, SEED + 1300));
    let carve = 1. - smooth(0.6, 1., lake);
    h = h * (1. - carve) + (2.35_f32 as f64 - 2.6) * carve;
    let hz = (z - 1.) * if z < 1. { 1.25 } else { 0.9 };
    let d = (x * x + hz * hz).sqrt() + fbm(x * 0.08, z * 0.08, SEED + 1700) * 0.9;
    h.max(84. - (d - 2.5).clamp(0., 6.) * 0.85 - (d - 8.5).max(0.) * 1.3)
}
// Foundation flattening is part of the terrain contract even before houses are drawn in P1.
const HOUSES: [[f64; 4]; 4] = [
    [38., -76., 5., 5.],
    [47., -67., 7., 5.],
    [57., -79., 5., 6.],
    [65., -69., 6., 5.],
];
pub fn height(x: f64, z: f64) -> f64 {
    static PADS: OnceLock<[f64; 4]> = OnceLock::new();
    let pads = PADS.get_or_init(|| {
        HOUSES.map(|[x, z, w, d]| (base_height(x + w / 2., z + d / 2.) + 0.5).floor())
    });
    let h = base_height(x, z);
    let mut nearest = None;
    let mut distance = 4.;
    for (i, [hx, hz, w, d]) in HOUSES.into_iter().enumerate() {
        let dx = (hx - 1. - x).max(x - (hx + w + 1.)).max(0.);
        let dz = (hz - 1. - z).max(z - (hz + d + 1.)).max(0.);
        let r = (dx * dx + dz * dz).sqrt();
        if r < distance {
            nearest = Some(i);
            distance = r;
        }
    }
    match nearest {
        Some(i) => h + (pads[i] - h) * (1. - smooth(0., 4., distance)),
        None => h,
    }
}
pub fn cell_height(x: f64, z: f64, size: i32) -> i32 {
    (height(x, z) / size as f64 + 0.5).floor() as i32 * size
}
pub struct Grid {
    heights: [Vec<i32>; 4],
}
impl Default for Grid {
    fn default() -> Self {
        Self::new()
    }
}
impl Grid {
    pub fn new() -> Self {
        Self {
            heights: BANDS.map(|b| {
                (0..b.columns() * b.rows())
                    .map(|i| {
                        cell_height(
                            b.min_x as f64
                                + (i % b.columns()) as f64 * b.size as f64
                                + 0.5 * b.size as f64,
                            b.min_z as f64
                                + (i / b.columns()) as f64 * b.size as f64
                                + 0.5 * b.size as f64,
                            b.size,
                        )
                    })
                    .collect()
            }),
        }
    }
    pub fn band(x: i32, z: i32) -> Option<usize> {
        BANDS.iter().position(|b| b.contains(x, z))
    }
    pub fn at(&self, x: i32, z: i32) -> i32 {
        match Self::band(x, z) {
            Some(i) => {
                let b = BANDS[i];
                self.heights[i][((z - b.min_z) / b.size) as usize * b.columns()
                    + ((x - b.min_x) / b.size) as usize]
            }
            None => FLOOR,
        }
    }
}
pub fn visible(x: i32, z: i32, size: i32) -> bool {
    let cx = x as f32 + size as f32 * 0.5;
    let cz = z as f32 + size as f32 * 0.5;
    let along = 20. - cz;
    let half = along * (66_f64.to_radians().tan() as f32) + size as f32;
    let offset = cx - 0.5;
    along >= -(size as f32)
        && (if offset < 0. {
            -offset <= half
        } else {
            offset <= half + 40.
        })
        && offset * offset + (cz - 14.) * (cz - 14.) <= 990. * 990.
}
fn top_material(h: i32, x: f64, z: f64, steep: i32, size: i32) -> M {
    if h <= 0 {
        return M::Gravel;
    }
    if (h as f32) < 2.35 {
        return M::Sand;
    }
    if z >= -215. {
        return M::GrassTop;
    }
    let line = 92. + 8. * fbm(x * 0.02, z * 0.02, SEED + 2100);
    if h as f64 >= line
        && steep <= 3 * size
        && noise(x * 0.05, z * 0.05, SEED + 2200, 2, false) <= 0.42
    {
        M::Snow
    } else if h as f64 >= line - 8. || (h >= 84 && steep > 2 * size) {
        M::Stone
    } else {
        M::GrassTop
    }
}
fn tint(m: M, h: i32) -> f32 {
    match m {
        M::Snow => 2.1,
        M::Stone if h >= 84 => 0.55,
        M::Stone => 0.8,
        _ => 1.,
    }
}
fn layers(m: M, size: i32, dirt: i32) -> Vec<(i32, i32, M)> {
    match m {
        M::GrassTop if size >= 4 => vec![
            (0, size, M::GrassTop),
            (size, size + 2, M::Dirt),
            (size + 2, i32::MAX, M::Stone),
        ],
        M::GrassTop => vec![
            (0, 1, M::GrassSide),
            (1, dirt, M::Dirt),
            (dirt, i32::MAX, M::Stone),
        ],
        M::Snow => vec![(0, 4, M::Snow), (4, i32::MAX, M::Stone)],
        M::Sand => vec![(0, 3, M::Sand), (3, i32::MAX, M::Stone)],
        M::Gravel => vec![(0, 2, M::Gravel), (2, i32::MAX, M::Stone)],
        _ => vec![(0, i32::MAX, m)],
    }
}
fn corner_ao(g: &Grid, x: i32, z: i32, sx: i32, sz: i32, h: i32) -> f32 {
    let sample = |dx: i32, dz: i32| {
        g.at(
            (x as f32 + dx as f32 * 0.5).floor() as i32,
            (z as f32 + dz as f32 * 0.5).floor() as i32,
        ) > h
    };
    let a = sample(sx, -sz);
    let b = sample(-sx, sz);
    let d = sample(sx, sz);
    1. - 0.14
        * (if a && b {
            3
        } else {
            a as i32 + b as i32 + d as i32
        }) as f32
}
fn top(builder: &mut Builder, m: M, rect: [i32; 4], h: i32, ao: [f32; 4]) {
    let [x, z, x1, z1] = rect;
    let p = [
        [x as f32, h as f32, z1 as f32],
        [x1 as f32, h as f32, z1 as f32],
        [x1 as f32, h as f32, z as f32],
        [x as f32, h as f32, z as f32],
    ];
    let t = p.map(|p| {
        if m == M::GrassTop {
            (0.93
                + 0.14 * (value(p[0] as f64 * 0.045, p[2] as f64 * 0.045, SEED + 2300) * 0.5 + 0.5))
                as f32
        } else {
            tint(m, h)
        }
    });
    builder.quad(m, p, [0., 1., 0.], ao, t, ao[0] + ao[2] < ao[1] + ao[3]);
}
#[derive(Debug, Default, PartialEq)]
pub struct Stats {
    pub cells: usize,
    pub merged: usize,
    pub tops: usize,
    pub sides: usize,
}
pub fn build(g: &Grid) -> anyhow::Result<(Mesh, Stats)> {
    let mut b = Builder::new();
    let stats = append(&mut b, g);
    Ok((b.finish()?, stats))
}
pub fn append(b: &mut Builder, g: &Grid) -> Stats {
    let mut stats = Stats::default();
    for (bi, band) in BANDS.into_iter().enumerate() {
        let size = band.size;
        let cols = band.columns();
        let rows = band.rows();
        let mut keys = vec![None; cols * rows];
        let mut used = vec![false; cols * rows];
        for iz in 0..rows {
            for ix in 0..cols {
                let x = band.min_x + ix as i32 * size;
                let z = band.min_z + iz as i32 * size;
                if Grid::band(x, z) != Some(bi) || !visible(x, z, size) {
                    continue;
                }
                let h = g.at(x, z);
                let steep = [
                    g.at(x - 1, z),
                    g.at(x + size, z),
                    g.at(x, z - 1),
                    g.at(x, z + size),
                ]
                .map(|n| (h - n).abs())
                .into_iter()
                .max()
                .unwrap();
                let m = top_material(
                    h,
                    x as f64 + size as f64 * 0.5,
                    z as f64 + size as f64 * 0.5,
                    steep,
                    size,
                );
                stats.cells += 1;
                let ao = [
                    corner_ao(g, x, z + size, -1, 1, h),
                    corner_ao(g, x + size, z + size, 1, 1, h),
                    corner_ao(g, x + size, z, 1, -1, h),
                    corner_ao(g, x, z, -1, -1, h),
                ];
                if ao == [1.; 4] {
                    keys[iz * cols + ix] = Some((h, m));
                } else {
                    top(b, m, [x, z, x + size, z + size], h, ao);
                    stats.tops += 1;
                }
                for (nx, nz) in [(1, 0), (-1, 0), (0, 1), (0, -1)] {
                    let mut start = 0;
                    let mut nh = i32::MIN;
                    for step in 0..=size {
                        let neighbor = if step == size {
                            i32::MIN
                        } else {
                            g.at(
                                if nx > 0 {
                                    x + size
                                } else if nx < 0 {
                                    x - 1
                                } else {
                                    x + step
                                },
                                if nz > 0 {
                                    z + size
                                } else if nz < 0 {
                                    z - 1
                                } else {
                                    z + step
                                },
                            )
                        };
                        if step == 0 {
                            nh = neighbor;
                            continue;
                        }
                        if neighbor != nh || step == size {
                            if nh < h {
                                let bottom = nh.max(FLOOR);
                                let dirt = if size == 1 {
                                    3 + (((noise(
                                        x as f64 * 0.09,
                                        z as f64 * 0.09,
                                        SEED + 2400,
                                        2,
                                        false,
                                    ) * 0.5
                                        + 0.5)
                                        * 3.)
                                        .floor() as i32)
                                        .clamp(0, 2)
                                } else {
                                    5
                                };
                                for (from, to, material) in layers(m, size, dirt) {
                                    let yt = h - from;
                                    let yb = if to == i32::MAX {
                                        bottom
                                    } else {
                                        (h - to).max(bottom)
                                    };
                                    if yt <= yb {
                                        continue;
                                    }
                                    let (xf, zf, s, a, e, lo, hi) = (
                                        x as f32,
                                        z as f32,
                                        size as f32,
                                        start as f32,
                                        step as f32,
                                        yb as f32,
                                        yt as f32,
                                    );
                                    let p = match (nx, nz) {
                                        (1, 0) => [
                                            [xf + s, lo, zf + e],
                                            [xf + s, lo, zf + a],
                                            [xf + s, hi, zf + a],
                                            [xf + s, hi, zf + e],
                                        ],
                                        (-1, 0) => [
                                            [xf, lo, zf + a],
                                            [xf, lo, zf + e],
                                            [xf, hi, zf + e],
                                            [xf, hi, zf + a],
                                        ],
                                        (0, 1) => [
                                            [xf + a, lo, zf + s],
                                            [xf + e, lo, zf + s],
                                            [xf + e, hi, zf + s],
                                            [xf + a, hi, zf + s],
                                        ],
                                        _ => [
                                            [xf + e, lo, zf],
                                            [xf + a, lo, zf],
                                            [xf + a, hi, zf],
                                            [xf + e, hi, zf],
                                        ],
                                    };
                                    let ba = if yb == bottom && bottom > FLOOR {
                                        0.86
                                    } else {
                                        1.
                                    };
                                    b.quad(
                                        material,
                                        p,
                                        [nx as f32, 0., nz as f32],
                                        [ba, ba, 1., 1.],
                                        [tint(material, h); 4],
                                        false,
                                    );
                                    stats.sides += 1;
                                }
                            }
                            start = step;
                            nh = neighbor;
                        }
                    }
                }
            }
        }
        for iz in 0..rows {
            let mut ix = 0;
            while ix < cols {
                let i = iz * cols + ix;
                let Some((h, m)) = keys[i] else {
                    ix += 1;
                    continue;
                };
                if used[i] {
                    ix += 1;
                    continue;
                }
                let mut w = 1;
                while ix + w < cols && keys[i + w] == keys[i] && !used[i + w] {
                    w += 1
                }
                let mut d = 1;
                'depth: while iz + d < rows {
                    for dx in 0..w {
                        let j = (iz + d) * cols + ix + dx;
                        if keys[j] != keys[i] || used[j] {
                            break 'depth;
                        }
                    }
                    d += 1;
                }
                for dz in 0..d {
                    for dx in 0..w {
                        used[(iz + dz) * cols + ix + dx] = true
                    }
                }
                let x = band.min_x + ix as i32 * size;
                let z = band.min_z + iz as i32 * size;
                top(
                    b,
                    m,
                    [x, z, x + w as i32 * size, z + d as i32 * size],
                    h,
                    [1.; 4],
                );
                stats.merged += 1;
                ix += w;
            }
        }
    }
    stats
}
pub fn left_woods_distance(x: f64, z: f64) -> f64 {
    let wx = (x + 88.0) / 52.0;
    let wz = (z + 88.0) / 40.0;
    (wx * wx + wz * wz).sqrt() + fbm(x * 0.03, z * 0.03, SEED + 1200) * 0.15
}

#[cfg(test)]
mod tests {
    use super::*;
    fn grid() -> &'static Grid {
        static G: OnceLock<Grid> = OnceLock::new();
        G.get_or_init(Grid::new)
    }
    #[test]
    fn hash_matches_kotlin_literals() {
        for (x, z, s, want) in [
            (0, 0, SEED, 0.04113400227630152),
            (3, -7, SEED, 0.7803994882344895),
            (-123456, 98765, SEED + 1013, 0.2124269731299265),
        ] {
            assert!((hash01(x, z, s) - want).abs() < 1e-15)
        }
    }
    #[test]
    fn noise_is_bounded_and_deterministic() {
        for i in 0..2000 {
            let x = i as f64 * 0.731 - 600.;
            let z = i as f64 * (-0.377) + 90.;
            let n = value(x, z, SEED);
            assert!((-1.0..=1.0).contains(&n));
            assert_eq!(n, value(x, z, SEED));
            assert!((0.0..=1.0).contains(&noise(x, z, SEED, 5, true)));
        }
    }
    #[test]
    fn bands_and_center_heights() {
        for pair in BANDS.windows(2) {
            for e in [pair[0].min_x, pair[0].max_x, pair[0].min_z, pair[0].max_z] {
                assert_eq!(e.rem_euclid(pair[1].size), 0)
            }
        }
        for (x, z) in [(0, 0), (-95, -95), (95, 39), (120, -150), (-400, -500)] {
            let b = BANDS[Grid::band(x, z).unwrap()];
            let cx = b.min_x + (x - b.min_x) / b.size * b.size;
            let cz = b.min_z + (z - b.min_z) / b.size * b.size;
            assert_eq!(
                grid().at(x, z),
                cell_height(
                    cx as f64 + b.size as f64 * 0.5,
                    cz as f64 + b.size as f64 * 0.5,
                    b.size
                )
            );
        }
        assert_eq!(grid().at(0, 1000), FLOOR);
    }
    #[test]
    fn summit_and_peaks() {
        for x in -1..=1 {
            for z in -1..=1 {
                assert_eq!(grid().at(x, z), 84)
            }
        }
        assert!(grid().at(0, -40) < 74);
        let max = (-500..500)
            .step_by(4)
            .flat_map(|x| (-576..-300).step_by(4).map(move |z| grid().at(x, z)))
            .max()
            .unwrap();
        assert!((95..=212).contains(&max));
    }
    #[test]
    fn mesh_counts_winding_and_lod_seams() {
        let (m, s) = build(grid()).unwrap();
        assert_eq!(
            s,
            Stats {
                cells: 81217,
                merged: 4994,
                tops: 36224,
                sides: 60912
            }
        );
        assert_eq!(m.vertices.len(), 408520);
        for tri in m.indices.chunks_exact(3) {
            let a = m.vertices[tri[0] as usize];
            let b = m.vertices[tri[1] as usize];
            let c = m.vertices[tri[2] as usize];
            let ab = glam::Vec3::from_array(b.position) - glam::Vec3::from_array(a.position);
            let ac = glam::Vec3::from_array(c.position) - glam::Vec3::from_array(a.position);
            assert!(ab.cross(ac).dot(glam::Vec3::from_array(a.normal)) > 0.);
        }
        let area: f32 = m
            .vertices
            .chunks_exact(4)
            .filter(|v| {
                v[0].normal[2].abs() == 1.
                    && v[0].position[2] == -96.
                    && v.iter().any(|v| v.position[0] > -96.)
                    && v.iter().any(|v| v.position[0] < 96.)
            })
            .map(|v| {
                let xs: [f32; 4] = std::array::from_fn(|i| v[i].position[0]);
                let ys: [f32; 4] = std::array::from_fn(|i| v[i].position[1]);
                (xs.iter().copied().fold(f32::NEG_INFINITY, f32::max)
                    - xs.iter().copied().fold(f32::INFINITY, f32::min))
                    * (ys.iter().copied().fold(f32::NEG_INFINITY, f32::max)
                        - ys.iter().copied().fold(f32::INFINITY, f32::min))
            })
            .sum();
        let expected: i32 = (-96..96)
            .filter(|&x| {
                let a = grid().at(x, -96);
                let b = grid().at(x, -97);
                if a > b {
                    visible(x, -96, 1)
                } else {
                    visible(x - x.rem_euclid(2), -98, 2)
                }
            })
            .map(|x| (grid().at(x, -96) - grid().at(x, -97)).abs())
            .sum();
        assert_eq!(area, expected as f32);
    }
}
