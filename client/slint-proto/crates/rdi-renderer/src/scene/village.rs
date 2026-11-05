use super::{
    mesh::{Builder, Material as M},
    surface::{self, Aabb},
};
#[derive(Clone, Copy)]
pub struct House {
    pub x: i32,
    pub z: i32,
    pub w: i32,
    pub d: i32,
    pub h: i32,
}
pub const HOUSES: [House; 4] = [
    House {
        x: 38,
        z: -76,
        w: 5,
        d: 5,
        h: 3,
    },
    House {
        x: 47,
        z: -67,
        w: 7,
        d: 5,
        h: 3,
    },
    House {
        x: 57,
        z: -79,
        w: 5,
        d: 6,
        h: 3,
    },
    House {
        x: 65,
        z: -69,
        w: 6,
        d: 5,
        h: 4,
    },
];
pub fn meadow(x: i32, z: i32) -> bool {
    (32..80).contains(&x) && (-62..-38).contains(&z)
}
pub fn near(x: i32, z: i32, margin: i32) -> bool {
    HOUSES.iter().any(|h| {
        x >= h.x - margin && x < h.x + h.w + margin && z >= h.z - margin && z < h.z + h.d + margin
    })
}
pub fn blocks(h: House) -> Vec<(M, Aabb)> {
    let pad =
        (super::terrain::base_height((h.x * 2 + h.w) as f64 / 2.0, (h.z * 2 + h.d) as f64 / 2.0)
            + 0.5)
            .floor() as f32;
    let (y0, y1) = (pad + 1.0, pad + 1.0 + h.h as f32);
    let (x0, x1, z0, z1) = (
        h.x as f32,
        (h.x + h.w) as f32,
        h.z as f32,
        (h.z + h.d) as f32,
    );
    let center = ((z0 + z1) / 2.0).floor();
    let mut b = vec![];
    let mut add = |m, min, max| b.push((m, Aabb::new(min, max)));
    for x in [x0, x1 - 1.0] {
        for z in [z0, z1 - 1.0] {
            add(M::OakLog, [x, y0, z], [x + 1.0, y1, z + 1.0]);
        }
    }
    add(
        M::SprucePlanks,
        [x0 + 1.0, y0, z1 - 0.5],
        [x0 + 2.0, y0 + 2.0, z1 + 0.06],
    );
    add(
        M::Glowstone,
        [x1 - 2.0, y0 + 1.0, z1 - 0.5],
        [x1 - 1.0, y0 + 2.0, z1 + 0.06],
    );
    add(
        M::Glowstone,
        [x1 - 0.5, y0 + 1.0, center],
        [x1 + 0.06, y0 + 2.0, center + 1.0],
    );
    add(
        M::Glowstone,
        [x0 - 0.06, y0 + 1.0, center],
        [x0 + 0.5, y0 + 2.0, center + 1.0],
    );
    add(
        M::Glowstone,
        [x0 + 2.0, y0 + 1.0, z0 - 0.06],
        [x0 + 3.0, y0 + 2.0, z0 + 0.5],
    );
    add(M::OakPlanks, [x0, y0, z0], [x1, y1, z1]);
    add(M::Cobblestone, [x0, pad, z0], [x1, y0, z1]);
    let mut layer = 0;
    loop {
        let za = z0 - 1.0 + layer as f32;
        let zb = z1 + 1.0 - layer as f32;
        if zb - za < 1.0 {
            break;
        }
        add(
            M::SprucePlanks,
            [x0 - 1.0, y1 + layer as f32, za],
            [x1 + 1.0, y1 + layer as f32 + 1.0, zb],
        );
        layer += 1;
    }
    add(
        M::Lantern,
        [x0 + 2.15, y0 + 1.6, z1 + 0.1],
        [x0 + 2.55, y0 + 2.0, z1 + 0.5],
    );
    b
}
pub fn append(builder: &mut Builder) -> usize {
    let mut quads = 0;
    for h in HOUSES {
        let parts = blocks(h);
        let boxes: Vec<_> = parts.iter().map(|p| p.1).collect();
        builder.check(surface::validate_planes(&boxes));
        for (i, (m, _)) in parts.iter().enumerate() {
            for (f, p) in surface::exposed(i, &boxes) {
                let ao = p.map(|corner| surface::corner_ao(i, corner, f, &boxes));
                builder.quad(*m, p, f.normal(), ao, [1.0; 4], false);
                quads += 1;
            }
        }
    }
    quads
}
