use super::{quantize, state::srgb_to_linear};
use anyhow::Result;
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(usize)]
pub enum Material {
    GrassTop,
    GrassSide,
    Dirt,
    Stone,
    Snow,
    Sand,
    Gravel,
    OakPlanks,
    OakLog,
    OakLogTop,
    BirchLog,
    SpruceLog,
    OakLeaves,
    BirchLeaves,
    SpruceLeaves,
    ShortGrass,
    SprucePlanks,
    Cobblestone,
    Glowstone,
    Lantern,
}
impl Material {
    pub const ALL: [Self; 20] = [
        Self::GrassTop,
        Self::GrassSide,
        Self::Dirt,
        Self::Stone,
        Self::Snow,
        Self::Sand,
        Self::Gravel,
        Self::OakPlanks,
        Self::OakLog,
        Self::OakLogTop,
        Self::BirchLog,
        Self::SpruceLog,
        Self::OakLeaves,
        Self::BirchLeaves,
        Self::SpruceLeaves,
        Self::ShortGrass,
        Self::SprucePlanks,
        Self::Cobblestone,
        Self::Glowstone,
        Self::Lantern,
    ];
    pub fn color(self) -> [f32; 3] {
        match self {
            Self::GrassTop | Self::ShortGrass => [0.62, 0.80, 0.38],
            Self::OakLeaves => [0.47, 0.67, 0.18],
            Self::BirchLeaves => [0.50, 0.66, 0.33],
            Self::SpruceLeaves => [0.38, 0.60, 0.38],
            Self::Glowstone => [1.0, 0.90, 0.70],
            Self::Lantern => [1.0, 0.86, 0.54],
            _ => [1.0; 3],
        }
        .map(srgb_to_linear)
    }
    pub fn cutout(self) -> bool {
        matches!(
            self,
            Self::OakLeaves
                | Self::BirchLeaves
                | Self::SpruceLeaves
                | Self::ShortGrass
                | Self::Lantern
        )
    }
    pub fn double_sided(self) -> bool {
        matches!(
            self,
            Self::OakLeaves | Self::BirchLeaves | Self::SpruceLeaves | Self::ShortGrass
        )
    }
    pub fn emissive(self) -> bool {
        matches!(self, Self::Glowstone | Self::Lantern)
    }
    pub fn v_scale(self) -> f32 {
        if self == Self::Lantern {
            1.0 / 3.0
        } else {
            1.0
        }
    }
    pub fn vegetation(self) -> bool {
        matches!(
            self,
            Self::OakLog
                | Self::BirchLog
                | Self::SpruceLog
                | Self::OakLeaves
                | Self::BirchLeaves
                | Self::SpruceLeaves
                | Self::ShortGrass
        )
    }
}
#[derive(Clone, Copy, bytemuck::Pod, bytemuck::Zeroable)]
#[repr(C)]
pub struct Vertex {
    pub position: [f32; 3],
    pub normal: [f32; 3],
    pub color: [f32; 3],
    pub kind: f32,
    pub uv: [f32; 2],
    pub ao: f32,
}
#[derive(Clone, Debug)]
pub struct MaterialBatch {
    pub material: Material,
    pub first: u32,
    pub count: u32,
}
pub struct Mesh {
    pub vertices: Vec<Vertex>,
    pub indices: Vec<u32>,
    pub batches: Vec<MaterialBatch>,
    pub displacement: quantize::Displacement,
}
pub struct Builder {
    vertices: [Vec<Vertex>; 20],
    indices: [Vec<u32>; 20],
    quantized: bool,
    error: Option<anyhow::Error>,
    displacement: quantize::Displacement,
}
impl Default for Builder {
    fn default() -> Self {
        Self::new()
    }
}
impl Builder {
    pub fn new() -> Self {
        Self::with_quantization(true)
    }
    pub fn with_quantization(quantized: bool) -> Self {
        Self {
            vertices: std::array::from_fn(|_| vec![]),
            indices: std::array::from_fn(|_| vec![]),
            quantized,
            error: None,
            displacement: Default::default(),
        }
    }
    pub fn check(&mut self, result: Result<()>) {
        if self.error.is_none() {
            self.error = result.err();
        }
    }
    pub fn quad(
        &mut self,
        m: Material,
        p: [[f32; 3]; 4],
        normal: [f32; 3],
        ao: [f32; 4],
        tint: [f32; 4],
        flip: bool,
    ) {
        self.quad_with(m, p, normal, ao, tint, flip, None, None);
    }
    #[allow(clippy::too_many_arguments)]
    pub fn quad_with(
        &mut self,
        m: Material,
        p: [[f32; 3]; 4],
        normal: [f32; 3],
        ao: [f32; 4],
        tint: [f32; 4],
        flip: bool,
        uv: Option<[[f32; 2]; 4]>,
        kinds: Option<[f32; 4]>,
    ) {
        if self.error.is_some() {
            return;
        }
        if let Err(e) = self.emit(m, p, normal, ao, tint, flip, uv, kinds) {
            self.error = Some(e.context(format!("emitting {m:?} quad")));
        }
    }
    #[allow(clippy::too_many_arguments)]
    fn emit(
        &mut self,
        m: Material,
        p: [[f32; 3]; 4],
        normal: [f32; 3],
        ao: [f32; 4],
        tint: [f32; 4],
        flip: bool,
        uv: Option<[[f32; 2]; 4]>,
        kinds: Option<[f32; 4]>,
    ) -> Result<()> {
        let emitted = if self.quantized {
            [
                quantize::point(p[0])?,
                quantize::point(p[1])?,
                quantize::point(p[2])?,
                quantize::point(p[3])?,
            ]
        } else {
            p
        };
        quantize::face(p, emitted, flip)?;
        let i = m as usize;
        let base = self.vertices[i].len() as u32;
        let color = m.color();
        for corner in 0..4 {
            // Every non-position attribute is computed from the original Kotlin coordinate.
            let [x, y, z] = p[corner];
            let projected = if normal[1].abs() > 0.5 {
                [x, z]
            } else if normal[0].abs() > 0.5 {
                [z, -y]
            } else {
                [x, -y]
            };
            self.vertices[i].push(Vertex {
                position: emitted[corner],
                normal,
                color: std::array::from_fn(|k| color[k] * tint[corner]),
                kind: kinds.map_or(0.0, |k| k[corner]),
                uv: uv.map_or(projected, |u| u[corner]),
                ao: ao[corner],
            });
            self.displacement.record(p[corner], emitted[corner]);
        }
        self.indices[i].extend(
            (if flip {
                [1, 2, 3, 1, 3, 0]
            } else {
                [0, 1, 2, 0, 2, 3]
            })
            .map(|v| base + v),
        );
        Ok(())
    }
    pub fn finish(self) -> Result<Mesh> {
        if let Some(error) = self.error {
            return Err(error);
        }
        let mut mesh = Mesh {
            vertices: vec![],
            indices: vec![],
            batches: vec![],
            displacement: self.displacement,
        };
        for (i, (vertices, indices)) in self.vertices.into_iter().zip(self.indices).enumerate() {
            let base = mesh.vertices.len() as u32;
            mesh.batches.push(MaterialBatch {
                material: Material::ALL[i],
                first: mesh.indices.len() as u32,
                count: indices.len() as u32,
            });
            mesh.indices.extend(indices.into_iter().map(|v| v + base));
            mesh.vertices.extend(vertices);
        }
        Ok(mesh)
    }
}

/// Local signed fixed-point positions, exactly 1/16 block per unit. The other packed fields
/// retain the P0.5 color/AO/UV representation so the accepted best image remains unchanged.
pub const POSITION_SCALE: f32 = 16.0;
#[repr(C)]
#[derive(Clone, Copy, bytemuck::Pod, bytemuck::Zeroable)]
pub struct PackedVertex {
    pub position: [i16; 4],
    pub normal: [i8; 4],
    pub color_ao: [u16; 4],
    pub uv: [u16; 2],
}
#[repr(C)]
#[derive(Clone, Copy, bytemuck::Pod, bytemuck::Zeroable)]
pub struct BatchParams {
    pub origin: [f32; 4],
    pub traits: [f32; 4],
}
fn fixed_axis(value: f32, origin: f32) -> anyhow::Result<i16> {
    anyhow::ensure!(
        value.is_finite() && origin.is_finite(),
        "non-finite packed position or origin"
    );
    anyhow::ensure!(
        (value * POSITION_SCALE).fract() == 0.0,
        "position {value} is not exactly representable at 1/16 block"
    );
    anyhow::ensure!(
        (origin * POSITION_SCALE).fract() == 0.0,
        "batch origin {origin} is not on the 1/16 grid"
    );
    let units = (value - origin) * POSITION_SCALE;
    anyhow::ensure!(
        units.fract() == 0.0 && (i16::MIN as f32..=i16::MAX as f32).contains(&units),
        "position {value} exceeds local i16 range around origin {origin}"
    );
    let encoded = units as i16;
    anyhow::ensure!(
        origin + encoded as f32 / POSITION_SCALE == value,
        "fixed-point position does not round-trip exactly"
    );
    Ok(encoded)
}
impl PackedVertex {
    pub fn pack(v: &Vertex, origin: [f32; 3]) -> anyhow::Result<Self> {
        let mut position = [0; 4];
        for i in 0..3 {
            position[i] = fixed_axis(v.position[i], origin[i])?;
        }
        anyhow::ensure!(
            v.kind.is_finite() && v.kind.fract() == 0.0 && (-32768.0..32768.0).contains(&v.kind),
            "packed kind out of range"
        );
        position[3] = v.kind as i16;
        Ok(Self {
            position,
            normal: [
                (v.normal[0] * 127.0) as i8,
                (v.normal[1] * 127.0) as i8,
                (v.normal[2] * 127.0) as i8,
                0,
            ],
            color_ao: [v.color[0], v.color[1], v.color[2], v.ao]
                .map(|v| half::f16::from_f32(v).to_bits()),
            uv: v.uv.map(|v| half::f16::from_f32(v).to_bits()),
        })
    }
    pub fn world_position(&self, origin: [f32; 3]) -> [f32; 3] {
        std::array::from_fn(|i| origin[i] + self.position[i] as f32 / POSITION_SCALE)
    }
}
#[derive(Debug)]
pub struct DrawRange {
    pub first: u32,
    pub count: u32,
    pub base: i32,
    pub origin: [f32; 3],
    pub material: Material,
}
fn quad_origin(vertices: &[Vertex]) -> [f32; 3] {
    std::array::from_fn(|axis| {
        let min = vertices
            .iter()
            .map(|v| v.position[axis])
            .fold(f32::INFINITY, f32::min);
        let max = vertices
            .iter()
            .map(|v| v.position[axis])
            .fold(f32::NEG_INFINITY, f32::max);
        // An integer origin is exact and keeps shader addition exact for the authored scene range.
        ((min + max) * 0.5).floor()
    })
}
/// Quad-aligned batches obey both u16 indexing and local-position range without changing a vertex.
/// A single primitive that cannot fit, or any coordinate off the approved grid, fails explicitly.
pub fn small_indices(mesh: &Mesh) -> anyhow::Result<(Vec<u16>, Vec<DrawRange>)> {
    const MAX_VERTICES: usize = 65532;
    anyhow::ensure!(
        mesh.vertices.len().is_multiple_of(4) && mesh.indices.len() == mesh.vertices.len() / 4 * 6,
        "expected indexed quad mesh"
    );
    for v in &mesh.vertices {
        for &p in &v.position {
            anyhow::ensure!(
                p.is_finite() && (p * POSITION_SCALE).fract() == 0.0,
                "position {p} is not exactly representable at 1/16 block"
            );
        }
    }
    let mut indices = Vec::with_capacity(mesh.indices.len());
    let mut ranges = vec![];
    let mut base = 0;
    while base < mesh.vertices.len() {
        let owner = mesh.batches.iter().find(|b| {
            b.count > 0
                && (b.first as usize..(b.first + b.count) as usize).contains(&(base / 4 * 6))
        });
        let material = owner.map_or(Material::GrassTop, |b| b.material);
        let material_end = owner.map_or(mesh.vertices.len(), |b| {
            (b.first + b.count) as usize / 6 * 4
        });
        let origin = quad_origin(&mesh.vertices[base..base + 4]);
        for v in &mesh.vertices[base..base + 4] {
            PackedVertex::pack(v, origin)?;
        }
        let mut end = base + 4;
        while end + 4 <= material_end && end + 4 - base <= MAX_VERTICES {
            if !mesh.vertices[end..end + 4]
                .iter()
                .all(|v| (0..3).all(|i| fixed_axis(v.position[i], origin[i]).is_ok()))
            {
                break;
            }
            end += 4;
        }
        let first = base / 4 * 6;
        let count = (end - base) / 4 * 6;
        for &index in &mesh.indices[first..first + count] {
            anyhow::ensure!(
                (base..end).contains(&(index as usize)),
                "quad crosses a local u16 batch"
            );
            indices.push((index - base as u32) as u16);
        }
        ranges.push(DrawRange {
            first: first as u32,
            count: count as u32,
            base: base as i32,
            origin,
            material,
        });
        base = end;
    }
    Ok((indices, ranges))
}
pub fn pack_mesh(mesh: &Mesh) -> anyhow::Result<(Vec<PackedVertex>, Vec<u16>, Vec<DrawRange>)> {
    let (indices, ranges) = small_indices(mesh)?;
    let mut vertices = Vec::with_capacity(mesh.vertices.len());
    for r in &ranges {
        let start = r.base as usize;
        let count = r.count as usize / 6 * 4;
        for v in &mesh.vertices[start..start + count] {
            vertices.push(PackedVertex::pack(v, r.origin)?);
        }
    }
    Ok((vertices, indices, ranges))
}
#[cfg(test)]
mod packed_tests {
    use super::*;
    fn vertex(position: [f32; 3]) -> Vertex {
        Vertex {
            position,
            normal: [0.0, 1.0, 0.0],
            color: [1.0; 3],
            kind: 0.0,
            uv: [0.0; 2],
            ao: 1.0,
        }
    }
    #[test]
    fn packed_geometry_and_indices_are_exact() {
        let (mesh, _) = super::super::terrain::build(&super::super::terrain::Grid::new()).unwrap();
        let (packed, indices, ranges) = pack_mesh(&mesh).unwrap();
        assert_eq!(std::mem::size_of::<PackedVertex>(), 24);
        assert_eq!(packed.len(), mesh.vertices.len());
        assert_eq!(indices.len(), mesh.indices.len());
        for r in ranges {
            let first = r.base as usize;
            for (i, value) in packed
                .iter()
                .enumerate()
                .skip(first)
                .take(r.count as usize / 6 * 4)
            {
                assert_eq!(
                    packed[i].world_position(r.origin),
                    mesh.vertices[i].position
                );
                for axis in 0..3 {
                    assert_eq!(
                        value.normal[axis] as f32 / 127.0,
                        mesh.vertices[i].normal[axis]
                    );
                    assert!(
                        (half::f16::from_bits(value.color_ao[axis]).to_f32()
                            - mesh.vertices[i].color[axis])
                            .abs()
                            < 0.001
                    );
                }
                assert_eq!(
                    value.uv.map(|v| half::f16::from_bits(v).to_f32()),
                    mesh.vertices[i].uv
                );
            }
            for i in r.first..r.first + r.count {
                assert_eq!(
                    indices[i as usize] as u32 + r.base as u32,
                    mesh.indices[i as usize]
                );
            }
        }
    }
    #[test]
    fn fractional_grid_positions_and_range_limits_are_exact() {
        let origin = [6000.0, -200.0, -9000.0];
        for units in [-32768, -17, -1, 0, 1, 17, 32767] {
            let p = std::array::from_fn(|i| origin[i] + units as f32 / 16.0);
            assert_eq!(
                PackedVertex::pack(&vertex(p), origin)
                    .unwrap()
                    .world_position(origin),
                p
            );
        }
        assert!(PackedVertex::pack(&vertex([2048.0, 0.0, 0.0]), [0.0; 3]).is_err());
        assert!(PackedVertex::pack(&vertex([-2048.0625, 0.0, 0.0]), [0.0; 3]).is_err());
        for bad in [0.06, 0.1, 0.15, f32::NAN, f32::INFINITY] {
            assert!(PackedVertex::pack(&vertex([bad, 0.0, 0.0]), [0.0; 3]).is_err());
        }
    }
    #[test]
    fn spatially_separated_quads_split_and_reconstruct() {
        let mut vertices = vec![];
        for x in [0.0, 6000.0] {
            for p in [
                [x, 0.0, 0.0],
                [x + 0.0625, 0.0, 0.0],
                [x + 0.0625, 0.0625, 0.0],
                [x, 0.0625, 0.0],
            ] {
                vertices.push(vertex(p));
            }
        }
        let mesh = Mesh {
            vertices,
            indices: vec![0, 1, 2, 0, 2, 3, 4, 5, 6, 4, 6, 7],
            batches: vec![],
            displacement: Default::default(),
        };
        let (vertices, indices, ranges) = pack_mesh(&mesh).unwrap();
        assert_eq!(ranges.len(), 2);
        for r in ranges {
            for (i, value) in vertices.iter().enumerate().skip(r.base as usize).take(4) {
                assert_eq!(value.world_position(r.origin), mesh.vertices[i].position);
            }
            for i in r.first..r.first + r.count {
                assert_eq!(
                    indices[i as usize] as u32 + r.base as u32,
                    mesh.indices[i as usize]
                );
            }
        }
    }
}
