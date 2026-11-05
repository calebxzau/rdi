//! Read-only P1 source-contract audit. No P1 geometry is quantized or submitted to the scene.
use anyhow::Result;
use rdi_renderer::scene::{
    mesh::{PackedVertex, Vertex},
    terrain::{Grid, hash01},
};
fn probe(name: &str, position: [f32; 3], source: &str) -> serde_json::Value {
    let v = Vertex {
        position,
        normal: [0.0, 1.0, 0.0],
        color: [1.0; 3],
        kind: 0.0,
        uv: [0.0; 2],
        ao: 1.0,
    };
    let result = PackedVertex::pack(&v, [0.0; 3]);
    serde_json::json!({"name":name,"position":position,"units":position.map(|v|v*16.0),"representable":result.is_ok(),"error":result.err().map(|e|e.to_string()),"source":source})
}
fn main() -> Result<()> {
    let grid = Grid::new();
    let pad = grid.at(40, -74) as f32;
    let mut examples = vec![
        probe(
            "first-house window outer Z",
            [41.0, pad + 2.0, -71.0 + 0.06],
            "BackgroundVillage.kt:85-89,108",
        ),
        probe(
            "first-house lantern min X/Z",
            [38.0 + 2.15, pad + 1.0 + 1.6, -71.0 + 0.1],
            "BackgroundVillage.kt:105",
        ),
    ];
    let mut tufts = 0;
    let mut vertices = 0;
    let mut rejected = 0;
    for z in -26_i32..14 {
        for x in -26_i32..27 {
            let chance = hash01(x, z, 0x7ee5 + 20) as f32;
            if chance >= 0.35
                || ((x as f32).abs() < 1.0 && (z as f32).abs() < 1.0)
                || [(4, 2), (-5, 1)].contains(&(x, z))
            {
                continue;
            }
            let height = grid.at(x, z);
            if !(72..=84).contains(&height) {
                continue;
            }
            tufts += 1;
            let tx = x as f32 + 0.3 + (hash01(x, z, 0x7ee5 + 21) as f32) * 0.4;
            let tz = z as f32 + 0.3 + (hash01(x, z, 0x7ee5 + 22) as f32) * 0.4;
            let size = 0.6 + 0.4 * chance / 0.35;
            let half = size * 0.45;
            for dz in [1.0, -1.0] {
                for position in [
                    [tx - half, height as f32, tz - half * dz],
                    [tx + half, height as f32, tz + half * dz],
                    [tx + half, height as f32 + size, tz + half * dz],
                    [tx - half, height as f32 + size, tz - half * dz],
                ] {
                    let entry = probe(
                        "grass tuft vertex",
                        position,
                        "BackgroundVegetation.kt:222-225,257-274",
                    );
                    vertices += 1;
                    if entry["representable"] == false {
                        rejected += 1;
                        if examples.len() < 5 {
                            examples.push(entry);
                        }
                    }
                }
            }
        }
    }
    let report = serde_json::json!({"unit":"1/16 block","policy":"reject off-grid coordinates; do not silently round","grass_tufts":tufts,"grass_vertices":vertices,"off_grid_grass_vertices":rejected,"examples":examples});
    println!("{}", serde_json::to_string_pretty(&report)?);
    anyhow::ensure!(tufts == 196, "grass placement parity changed");
    anyhow::ensure!(
        rejected > 0,
        "expected P1 source-contract conflict was not reproduced"
    );
    Ok(())
}
