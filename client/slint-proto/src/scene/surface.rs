//! Static boxes, exposed-face subtraction and AO. All operations use original unquantized coordinates.
use super::{
    mesh::{Builder, Material},
    quantize,
};
#[derive(Clone, Copy, Debug)]
pub struct Aabb {
    pub min: [f32; 3],
    pub max: [f32; 3],
}
impl Aabb {
    pub fn new(min: [f32; 3], max: [f32; 3]) -> Self {
        Self { min, max }
    }
    pub fn corners(self) -> [[f32; 3]; 8] {
        std::array::from_fn(|i| {
            std::array::from_fn(|a| {
                if i & (1 << a) == 0 {
                    self.min[a]
                } else {
                    self.max[a]
                }
            })
        })
    }
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(usize)]
pub enum Face {
    Pz,
    Nz,
    Nx,
    Px,
    Py,
    Ny,
}
impl Face {
    pub const ALL: [Self; 6] = [Self::Pz, Self::Nz, Self::Nx, Self::Px, Self::Py, Self::Ny];
    fn axes(self) -> (usize, usize, usize, f32, f32) {
        match self {
            Self::Pz => (2, 0, 1, 1.0, 1.0),
            Self::Nz => (2, 0, 1, -1.0, 1.0),
            Self::Nx => (0, 2, 1, 1.0, 1.0),
            Self::Px => (0, 2, 1, -1.0, 1.0),
            Self::Py => (1, 0, 2, 1.0, -1.0),
            Self::Ny => (1, 0, 2, 1.0, 1.0),
        }
    }
    pub fn normal(self) -> [f32; 3] {
        match self {
            Self::Pz => [0.0, 0.0, 1.0],
            Self::Nz => [0.0, 0.0, -1.0],
            Self::Nx => [-1.0, 0.0, 0.0],
            Self::Px => [1.0, 0.0, 0.0],
            Self::Py => [0.0, 1.0, 0.0],
            Self::Ny => [0.0, -1.0, 0.0],
        }
    }
    fn positive(self) -> bool {
        matches!(self, Self::Pz | Self::Px | Self::Py)
    }
    fn plane(self, b: Aabb) -> f32 {
        let a = self.axes().0;
        if self.positive() { b.max[a] } else { b.min[a] }
    }
    fn rect(self, b: Aabb) -> Rect {
        let (_, u, v, _, _) = self.axes();
        Rect {
            u0: b.min[u],
            u1: b.max[u],
            v0: b.min[v],
            v1: b.max[v],
        }
    }
    fn points(self, b: Aabb, r: Rect) -> [[f32; 3]; 4] {
        let (a, u, v, su, sv) = self.axes();
        let (us, ue) = if su > 0.0 { (r.u0, r.u1) } else { (r.u1, r.u0) };
        let (vs, ve) = if sv > 0.0 { (r.v0, r.v1) } else { (r.v1, r.v0) };
        [(us, vs), (ue, vs), (ue, ve), (us, ve)].map(|(x, y)| {
            let mut p = [0.0; 3];
            p[a] = self.plane(b);
            p[u] = x;
            p[v] = y;
            p
        })
    }
}
#[derive(Clone, Copy, Debug)]
struct Rect {
    u0: f32,
    u1: f32,
    v0: f32,
    v1: f32,
}
const EPS: f32 = 0.0001;
fn overlap(a: Rect, b: Rect) -> bool {
    a.u1.min(b.u1) - a.u0.max(b.u0) > EPS && a.v1.min(b.v1) - a.v0.max(b.v0) > EPS
}
fn subtract(a: Rect, b: Rect) -> Vec<Rect> {
    if !overlap(a, b) {
        return vec![a];
    }
    let (l, r, lo, hi) = (
        a.u0.max(b.u0),
        a.u1.min(b.u1),
        a.v0.max(b.v0),
        a.v1.min(b.v1),
    );
    let mut out = vec![];
    if l - a.u0 > EPS {
        out.push(Rect {
            u0: a.u0,
            u1: l,
            v0: a.v0,
            v1: a.v1,
        })
    }
    if a.u1 - r > EPS {
        out.push(Rect {
            u0: r,
            u1: a.u1,
            v0: a.v0,
            v1: a.v1,
        })
    }
    if lo - a.v0 > EPS {
        out.push(Rect {
            u0: l,
            u1: r,
            v0: a.v0,
            v1: lo,
        })
    }
    if a.v1 - hi > EPS {
        out.push(Rect {
            u0: l,
            u1: r,
            v0: hi,
            v1: a.v1,
        })
    }
    out
}
fn merge(mut rects: Vec<Rect>) -> Vec<Rect> {
    loop {
        let mut found = None;
        'scan: for i in 0..rects.len() {
            for j in i + 1..rects.len() {
                let (a, b) = (rects[i], rects[j]);
                let joined = if (a.v0 - b.v0).abs() <= EPS
                    && (a.v1 - b.v1).abs() <= EPS
                    && ((a.u1 - b.u0).abs() <= EPS || (b.u1 - a.u0).abs() <= EPS)
                {
                    Some(Rect {
                        u0: a.u0.min(b.u0),
                        u1: a.u1.max(b.u1),
                        v0: a.v0,
                        v1: a.v1,
                    })
                } else if (a.u0 - b.u0).abs() <= EPS
                    && (a.u1 - b.u1).abs() <= EPS
                    && ((a.v1 - b.v0).abs() <= EPS || (b.v1 - a.v0).abs() <= EPS)
                {
                    Some(Rect {
                        u0: a.u0,
                        u1: a.u1,
                        v0: a.v0.min(b.v0),
                        v1: a.v1.max(b.v1),
                    })
                } else {
                    None
                };
                if let Some(r) = joined {
                    found = Some((i, j, r));
                    break 'scan;
                }
            }
        }
        if let Some((i, j, r)) = found {
            rects[i] = r;
            rects.remove(j);
        } else {
            return rects;
        }
    }
}
pub fn exposed(index: usize, boxes: &[Aabb]) -> Vec<(Face, [[f32; 3]; 4])> {
    let source = boxes[index];
    let mut out = vec![];
    for face in Face::ALL {
        let mut rects = vec![face.rect(source)];
        let plane = face.plane(source);
        let axis = face.axes().0;
        for (j, &b) in boxes.iter().enumerate() {
            if j == index {
                continue;
            }
            let rect = face.rect(b);
            if !rects.iter().any(|&r| overlap(r, rect)) {
                continue;
            }
            let positive = face.positive();
            let outward = if positive {
                b.max[axis] > plane + EPS
            } else {
                b.min[axis] < plane - EPS
            };
            let reaches = if positive {
                b.min[axis] <= plane + EPS
            } else {
                b.max[axis] >= plane - EPS
            };
            let coplanar = if positive {
                (b.max[axis] - plane).abs() <= EPS && b.min[axis] < plane - EPS
            } else {
                (b.min[axis] - plane).abs() <= EPS && b.max[axis] > plane + EPS
            };
            if !(outward && reaches || coplanar && j < index) {
                continue;
            }
            rects = rects.into_iter().flat_map(|r| subtract(r, rect)).collect();
        }
        let mut rects = merge(rects);
        rects.sort_by(|a, b| {
            a.u0.total_cmp(&b.u0)
                .then(a.v0.total_cmp(&b.v0))
                .then(a.u1.total_cmp(&b.u1))
                .then(a.v1.total_cmp(&b.v1))
        });
        out.extend(rects.into_iter().map(|r| (face, face.points(source, r))));
    }
    out
}
pub fn corner_ao(index: usize, corner: [f32; 3], face: Face, boxes: &[Aabb]) -> f32 {
    let b = boxes[index];
    let n = face.normal();
    let (a, c) = if n[0].abs() > 0.5 {
        (1, 2)
    } else if n[1].abs() > 0.5 {
        (0, 2)
    } else {
        (0, 1)
    };
    let sa = if corner[a] - (b.min[a] + b.max[a]) * 0.5 >= 0.0 {
        1.0
    } else {
        -1.0
    };
    let sc = if corner[c] - (b.min[c] + b.max[c]) * 0.5 >= 0.0 {
        1.0
    } else {
        -1.0
    };
    let occupied = |oa: f32, oc: f32| {
        let mut p = std::array::from_fn::<_, 3, _>(|i| corner[i] + n[i] * 0.02);
        p[a] += oa;
        p[c] += oc;
        boxes.iter().enumerate().any(|(j, b)| {
            j != index
                && (0..3).all(|i| {
                    p[i] >= b.min[i] - n[i].abs() * 0.02 && p[i] <= b.max[i] + n[i].abs() * 0.02
                })
        })
    };
    let x = occupied(sa * 0.02, 0.0);
    let y = occupied(0.0, sc * 0.02);
    let d = occupied(sa * 0.02, sc * 0.02);
    1.0 - 0.14
        * (if x && y {
            3
        } else {
            x as i32 + y as i32 + d as i32
        }) as f32
}
pub fn emit_box(builder: &mut Builder, m: Material, b: Aabb, tint: f32, skip: &[Face]) {
    builder.check(quantize::extent(b.min, b.max));
    for f in Face::ALL {
        if !skip.contains(&f) {
            builder.quad(
                m,
                f.points(b, f.rect(b)),
                f.normal(),
                [1.0; 4],
                [tint; 4],
                false,
            );
        }
    }
}
/// Preserve every distinct authored boundary plane within a compound part, not only face size.
pub fn validate_planes(boxes: &[Aabb]) -> anyhow::Result<()> {
    for b in boxes {
        quantize::extent(b.min, b.max)?;
    }
    for axis in 0..3 {
        let mut p: Vec<_> = boxes
            .iter()
            .flat_map(|b| [b.min[axis], b.max[axis]])
            .collect();
        p.sort_by(f32::total_cmp);
        p.dedup();
        for pair in p.windows(2) {
            quantize::offset(pair[0], pair[1])?;
        }
    }
    Ok(())
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn duplicate_and_neighbor_faces_are_removed() {
        let a = Aabb::new([0.0; 3], [1.0; 3]);
        assert_eq!(exposed(0, &[a, a]).len(), 6);
        assert_eq!(exposed(1, &[a, a]).len(), 0);
        let b = Aabb::new([1.0, 0.0, 0.0], [2.0, 1.0, 1.0]);
        assert_eq!(exposed(0, &[a, b]).len(), 5);
    }
    #[test]
    fn compound_outset_cannot_collapse() {
        assert!(
            validate_planes(&[
                Aabb::new([0.0; 3], [1.0; 3]),
                Aabb::new([0.2, 0.2, 1.0], [0.8, 0.8, 1.01])
            ])
            .is_err()
        );
    }
}
