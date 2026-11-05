//! Deterministic Kotlin placements and shape construction; only Builder changes emitted positions.
use super::{
    mesh::{Builder, Material as M},
    quantize,
    surface::{self, Aabb, Face},
    terrain::{self, Grid, hash01},
    village,
};
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Species {
    Oak,
    Birch,
    Spruce,
}
impl Species {
    fn log(self) -> M {
        match self {
            Self::Oak => M::OakLog,
            Self::Birch => M::BirchLog,
            Self::Spruce => M::SpruceLog,
        }
    }
    fn leaves(self) -> M {
        match self {
            Self::Oak => M::OakLeaves,
            Self::Birch => M::BirchLeaves,
            Self::Spruce => M::SpruceLeaves,
        }
    }
}
#[derive(Clone, Debug)]
pub struct Tree {
    pub x: i32,
    pub z: i32,
    pub y: i32,
    pub species: Species,
    pub height: i32,
    pub detailed: bool,
    pub distant: bool,
    pub tint: f32,
}
#[derive(Clone, Debug)]
pub struct Tuft {
    pub x: f32,
    pub y: f32,
    pub z: f32,
    pub size: f32,
    pub tint: f32,
}
fn smooth(a: f64, b: f64, x: f64) -> f64 {
    let t = ((x - a) / (b - a)).clamp(0.0, 1.0);
    t * t * (3.0 - 2.0 * t)
}
pub fn trees(g: &Grid) -> Vec<Tree> {
    let eye = super::state::camera(0.0).0;
    let mut trees = vec![];
    for cz in -152..=4 {
        for cx in -180..=180 {
            let jx = hash01(cx, cz, 0x7ee5);
            let jz = hash01(cx, cz, 0x7ee6);
            let keep = hash01(cx, cz, 0x7ee7);
            let variant = hash01(cx, cz, 0x7ee8);
            let x = cx * 5 + (jx * 4.0).floor() as i32;
            let z = cz * 5 + (jz * 4.0).floor() as i32;
            if !terrain::visible(x, z, 1) {
                continue;
            }
            let y = g.at(x, z);
            let high = z < -215;
            if (y as f32) < 2.35 + 1.0 || high && y > 76 {
                continue;
            }
            let px = x as f32;
            let pz = z as f32;
            if px * px + pz * pz < 225.0
                || village::near(x, z, 4)
                || village::meadow(x, z)
                || terrain::lake_distance(x as f64 + 0.5, z as f64 + 0.5) < 1.3
            {
                continue;
            }
            let steep = [
                g.at(x - 2, z),
                g.at(x + 2, z),
                g.at(x, z - 2),
                g.at(x, z + 2),
            ]
            .map(|n| (y - n).abs())
            .into_iter()
            .max()
            .unwrap();
            if steep > 4 {
                continue;
            }
            let forest = terrain::fbm(x as f64 * 0.011, z as f64 * 0.011, 0x7ee5 + 10);
            let woods = 1.0
                - smooth(
                    0.8,
                    1.1,
                    terrain::left_woods_distance(x as f64 + 0.5, z as f64 + 0.5),
                );
            if keep >= smooth(-0.55, 0.05, forest).max(woods) * 0.95 {
                continue;
            }
            let species = if high && y >= 34 || high && y >= 26 && variant < 0.5 {
                Species::Spruce
            } else if high && y >= 26 {
                Species::Oak
            } else if variant < 0.25 {
                Species::Birch
            } else {
                Species::Oak
            };
            let h = match species {
                Species::Oak => 4 + (variant * 3.0).floor() as i32,
                Species::Birch => 5 + (variant * 3.0).floor() as i32,
                Species::Spruce => 6 + (variant * 4.0).floor() as i32,
            };
            let dx = x as f32 + 0.5 - eye.x;
            let dz = z as f32 + 0.5 - eye.z;
            let distance = (dx * dx + dz * dz).sqrt();
            let detailed = distance < 150.0;
            let distant = distance >= 420.0;
            let thinning = hash01(cx, cz, 0x7ee5 + 4);
            if detailed && thinning < 0.4 || distant && thinning < 0.5 {
                continue;
            }
            trees.push(Tree {
                x,
                z,
                y,
                species,
                height: h,
                detailed,
                distant,
                tint: (0.88 + 0.22 * jz) as f32,
            });
        }
    }
    trees.sort_by(|a, b| {
        let d = |t: &Tree| {
            let x = t.x as f32 + 0.5 - eye.x;
            let z = t.z as f32 + 0.5 - eye.z;
            x * x + z * z
        };
        d(a).total_cmp(&d(b))
    });
    trees
}
pub fn hidden(b: Aabb) -> Vec<Face> {
    let mut skip = vec![Face::Nz];
    let span = 0.06_f32.sin() * 13.5;
    if b.min[0] > 0.5 + span {
        skip.push(Face::Px)
    }
    if b.max[0] < 0.5 - span {
        skip.push(Face::Nx)
    }
    if b.min[1] <= 84.0 + 6.5 - 0.25 {
        skip.push(Face::Ny)
    }
    skip
}
pub fn emit_tree(b: &mut Builder, t: &Tree) {
    let (x, z, y, top) = (t.x as f32, t.z as f32, t.y as f32, (t.y + t.height) as f32);
    if t.distant {
        let radius = if t.species == Species::Spruce {
            1.0
        } else {
            2.0
        };
        let bottom = if t.species == Species::Spruce {
            y + 2.0
        } else {
            top - 2.0
        };
        let a = Aabb::new(
            [x - radius, bottom, z - radius],
            [x + 1.0 + radius, top + 1.0, z + 1.0 + radius],
        );
        surface::emit_box(b, t.species.leaves(), a, t.tint, &hidden(a));
        return;
    }
    let mut emit = |m, a: Aabb, tint, skip: &[Face]| {
        let mut omitted = skip.to_vec();
        if !t.detailed {
            omitted.extend(hidden(a));
        }
        surface::emit_box(b, m, a, tint, &omitted);
    };
    emit(
        t.species.log(),
        Aabb::new([x, y, z], [x + 1.0, top, z + 1.0]),
        1.0,
        &[Face::Py, Face::Ny],
    );
    let leaf = |radius: f32, bottom: f32, height: f32| {
        Aabb::new(
            [x - radius, bottom, z - radius],
            [x + 1.0 + radius, bottom + height, z + 1.0 + radius],
        )
    };
    match t.species {
        Species::Oak | Species::Birch => {
            let cross = |r: f32, bottom: f32, h: f32| {
                let inner = r - 1.0;
                [
                    Aabb::new(
                        [x - r, bottom, z - inner],
                        [x + 1.0 + r, bottom + h, z + 1.0 + inner],
                    ),
                    Aabb::new(
                        [x - inner, bottom, z - r],
                        [x + 1.0 + inner, bottom + h, z + 1.0 + r],
                    ),
                ]
            };
            if t.detailed {
                for a in cross(2.0, top - 2.0, 2.0) {
                    emit(t.species.leaves(), a, t.tint, &[]);
                }
                emit(t.species.leaves(), leaf(1.0, top, 1.0), t.tint, &[]);
                for a in cross(1.0, top + 1.0, 1.0) {
                    emit(t.species.leaves(), a, t.tint, &[]);
                }
            } else {
                for a in cross(2.0, top - 2.0, 3.0) {
                    emit(t.species.leaves(), a, t.tint, &[]);
                }
            }
        }
        Species::Spruce => {
            if t.detailed {
                let mut at = top - 1.0;
                let mut layer = 0;
                while at >= y + 2.0 {
                    let r = if layer % 2 == 0 || at > top - 3.0 {
                        1.0
                    } else {
                        2.0
                    };
                    emit(t.species.leaves(), leaf(r, at, 1.0), t.tint, &[]);
                    at -= 1.0;
                    layer += 1;
                }
                emit(t.species.leaves(), leaf(0.0, top, 1.0), t.tint, &[]);
            } else {
                emit(
                    t.species.leaves(),
                    leaf(1.0, y + 2.0, top - y - 3.0),
                    t.tint,
                    &[],
                );
                emit(t.species.leaves(), leaf(0.0, top - 1.0, 3.0), t.tint, &[]);
            }
        }
    }
}
pub fn tufts(g: &Grid) -> Vec<Tuft> {
    let mut out = vec![];
    for z in -26_i32..14 {
        for x in -26_i32..27 {
            let chance = hash01(x, z, 0x7ee5 + 20) as f32;
            if chance >= 0.35 || x == 0 && z == 0 || [(4, 2), (-5, 1)].contains(&(x, z)) {
                continue;
            }
            let y = g.at(x, z);
            if !(72..=84).contains(&y) {
                continue;
            }
            let ox = hash01(x, z, 0x7ee5 + 21) as f32;
            let oz = hash01(x, z, 0x7ee5 + 22) as f32;
            out.push(Tuft {
                x: x as f32 + 0.3 + ox * 0.4,
                y: y as f32,
                z: z as f32 + 0.3 + oz * 0.4,
                size: 0.6 + 0.4 * chance / 0.35,
                tint: 0.92 + 0.16 * ox,
            });
        }
    }
    out
}
pub fn emit_tuft(b: &mut Builder, t: &Tuft) {
    let half = t.size * 0.45;
    let top = t.y + t.size;
    b.check(quantize::extent(
        [t.x - half, t.y, t.z - half],
        [t.x + half, top, t.z + half],
    ));
    // The original continuous placement/size is preserved. Only emitted points are rounded by Builder.
    let uv = [
        [0.001, 0.999],
        [0.999, 0.999],
        [0.999, 0.001],
        [0.001, 0.001],
    ];
    for dz in [1.0, -1.0] {
        let p = [
            [t.x - half, t.y, t.z - half * dz],
            [t.x + half, t.y, t.z + half * dz],
            [t.x + half, top, t.z + half * dz],
            [t.x - half, top, t.z - half * dz],
        ];
        b.quad_with(
            M::ShortGrass,
            p,
            [0.0, 1.0, 0.0],
            [1.0; 4],
            [t.tint; 4],
            false,
            Some(uv),
            Some([0.0, 0.0, 2.0, 2.0]),
        );
    }
}
#[derive(Clone, Debug, serde::Serialize)]
pub struct Stats {
    pub trees: usize,
    pub detailed_trees: usize,
    pub grass_tufts: usize,
}
pub fn append(b: &mut Builder, g: &Grid) -> Stats {
    let trees = trees(g);
    for t in &trees {
        emit_tree(b, t);
    }
    let tufts = tufts(g);
    for t in &tufts {
        emit_tuft(b, t);
    }
    for (x, z, m) in [(4, 2, M::Cobblestone), (-5, 1, M::Stone)] {
        let y = g.at(x, z) as f32;
        surface::emit_box(
            b,
            m,
            Aabb::new(
                [x as f32, y, z as f32],
                [x as f32 + 1.0, y + 1.0, z as f32 + 1.0],
            ),
            0.8,
            &[Face::Ny],
        );
    }
    Stats {
        trees: trees.len(),
        detailed_trees: trees.iter().filter(|t| t.detailed).count(),
        grass_tufts: tufts.len(),
    }
}
