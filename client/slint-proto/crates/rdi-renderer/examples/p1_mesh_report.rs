//! Independent CPU audit of the approved generator deviation. No account/network access.
use anyhow::Result;
use rdi_renderer::scene::{geometry, mesh, quantize, surface, village};
fn main() -> Result<()> {
    let (m, vegetation, terrain) = geometry::build(true)?;
    let (packed, indices, ranges) = mesh::pack_mesh(&m)?;
    for r in &ranges {
        for (i, v) in packed
            .iter()
            .enumerate()
            .skip(r.base as usize)
            .take(r.count as usize / 6 * 4)
        {
            anyhow::ensure!(
                v.world_position(r.origin) == m.vertices[i].position,
                "packed position mismatch"
            );
        }
        for i in r.first..r.first + r.count {
            anyhow::ensure!(
                indices[i as usize] as u32 + r.base as u32 == m.indices[i as usize],
                "index mismatch"
            );
        }
    }
    let mut offsets = vec![];
    for h in village::HOUSES {
        let parts = village::blocks(h);
        let boxes: Vec<_> = parts.iter().map(|p| p.1).collect();
        surface::validate_planes(&boxes)?;
        let wall_z = (h.z + h.d) as f32;
        offsets.push(serde_json::json!({"house":[h.x,h.z],"window_outset_original":0.06,"window_outset_emitted":quantize::scalar(wall_z+0.06)?-quantize::scalar(wall_z)?}));
    }
    println!(
        "{}",
        serde_json::to_string_pretty(
            &serde_json::json!({"vegetation":vegetation,"terrain":format!("{terrain:?}"),"vertices":m.vertices.len(),"indices":m.indices.len(),"packed_bytes":packed.len()*24+indices.len()*2,"batches":ranges.len(),"displacement":m.displacement.report(),"outsets":offsets,"checks":{"positions_round_trip":true,"indices_reconstruct":true,"non_position_attributes":"covered by complete_scene_quantizes_only_positions_and_round_trips test","zero_area_and_winding":"checked for every emitted face by Builder","part_extent_and_plane_sign":"checked before all boxes and compound village emission","shadow_input":"same GpuMesh buffers as main rendering"}})
        )?
    );
    Ok(())
}
