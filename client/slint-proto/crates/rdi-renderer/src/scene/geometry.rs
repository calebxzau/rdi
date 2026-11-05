//! P1 authored mesh. All decisions precede emitted-position quantization.
use super::{
    mesh::{Builder, Mesh},
    terrain::{self, Grid},
    vegetation, village,
};
use anyhow::{Result, ensure};
pub fn build(quantized: bool) -> Result<(Mesh, vegetation::Stats, terrain::Stats)> {
    let grid = Grid::new();
    let mut b = Builder::with_quantization(quantized);
    let terrain = terrain::append(&mut b, &grid);
    village::append(&mut b);
    let vegetation = vegetation::append(&mut b, &grid);
    let mesh = b.finish()?;
    // Kotlin includes one expanded water quad (6 vertices/indices); that pass belongs to P2.
    ensure!(
        mesh.vertices.len() + 6 == 714386 && mesh.indices.len() + 6 == 1071576,
        "P1 geometry count differs from Kotlin: {} vertices / {} indices",
        mesh.vertices.len(),
        mesh.indices.len()
    );
    ensure!(
        vegetation.trees == 13173
            && vegetation.detailed_trees == 491
            && vegetation.grass_tufts == 196,
        "vegetation count differs from Kotlin: {vegetation:?}"
    );
    ensure!(
        mesh.vertices.len() <= 900000,
        "scene vertex budget exceeded"
    );
    Ok((mesh, vegetation, terrain))
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn complete_scene_quantizes_only_positions_and_round_trips() {
        let (raw, _, _) = build(false).unwrap();
        let (q, _, _) = build(true).unwrap();
        assert_eq!(raw.indices, q.indices);
        assert_eq!(raw.vertices.len(), q.vertices.len());
        for (a, b) in raw.vertices.iter().zip(&q.vertices) {
            assert_eq!(&bytemuck::bytes_of(a)[12..], &bytemuck::bytes_of(b)[12..]);
            for i in 0..3 {
                assert_eq!(b.position[i], (a.position[i] * 16.0).round() / 16.0);
                assert!((a.position[i] - b.position[i]).abs() <= 1.0 / 32.0);
            }
        }
        let (packed, indices, ranges) = super::super::mesh::pack_mesh(&q).unwrap();
        assert_eq!(indices.len(), raw.indices.len());
        for r in ranges {
            for (i, p) in packed
                .iter()
                .enumerate()
                .skip(r.base as usize)
                .take(r.count as usize / 6 * 4)
            {
                assert_eq!(p.world_position(r.origin), q.vertices[i].position);
            }
            for i in r.first..r.first + r.count {
                assert_eq!(
                    indices[i as usize] as u32 + r.base as u32,
                    q.indices[i as usize]
                );
            }
        }
    }
}
