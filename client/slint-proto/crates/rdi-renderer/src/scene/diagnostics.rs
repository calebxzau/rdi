//! Opt-in P0.5 allocation instrumentation. No sampling, sleeps or extra GPU objects in normal runs.
use std::sync::atomic::{AtomicBool, Ordering};
static ENABLED: AtomicBool = AtomicBool::new(false);
pub fn enable(value: bool) {
    ENABLED.store(value, Ordering::Relaxed);
}
pub fn snapshot(device: &wgpu::Device, phase: &str) {
    if !ENABLED.load(Ordering::Relaxed) {
        return;
    }
    #[cfg(feature = "allocation-probe")]
    crate::allocation_probe::snapshot(phase);
    let report = device.generate_allocator_report().map(|r| serde_json::json!({
        "allocated": r.total_allocated_bytes, "reserved": r.total_reserved_bytes,
        "allocations": r.allocations.iter().map(|a|serde_json::json!({"name":a.name,"offset":a.offset,"size":a.size})).collect::<Vec<_>>(),
        "blocks": r.blocks.iter().map(|b|serde_json::json!({"size":b.size,"start":b.allocations.start,"end":b.allocations.end})).collect::<Vec<_>>()
    }));
    tracing::info!(data=%serde_json::json!({"phase":phase,"report":report}), "Memory allocator snapshot");
}
pub fn resource(label: &str, bytes: u64) {
    if ENABLED.load(Ordering::Relaxed) {
        tracing::info!(label, bytes, "Memory resource payload");
    }
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PostStage {
    Fog,
    Copy,
    Mip,
    Bloom,
    Final,
}
impl PostStage {
    fn parse(value: &str) -> anyhow::Result<Self> {
        Ok(match value {
            "fog" => Self::Fog,
            "copy" => Self::Copy,
            "mip" => Self::Mip,
            "bloom" => Self::Bloom,
            "final" => Self::Final,
            _ => anyhow::bail!("Unknown post stage: {value}"),
        })
    }
}
#[derive(Clone, Copy, Debug)]
pub struct Options {
    pub samples: u32,
    pub packed: bool,
    pub chunk_upload: bool,
    pub lazy_targets: bool,
    pub pipelines_only: bool,
    pub probe: bool,
    pub probe_unfocused: bool,
    pub delay_ms: u64,
    pub drop_after: Option<u64>,
    pub p1_only: bool,
    pub p2_only: bool,
    pub p3_only: bool,
    pub no_crowd: bool,
    pub legacy_sampler: bool,
    pub fail: bool,
    pub startup_fail: bool,
    pub low_performance_ms: f64,
    pub skip_post: Option<PostStage>,
    pub post_create_only: bool,
    pub steve_only: bool,
    pub reflection_first: bool,
    pub probe_hold_ms: u64,
}
impl Default for Options {
    fn default() -> Self {
        Self {
            samples: 1,
            packed: true,
            chunk_upload: false,
            lazy_targets: false,
            pipelines_only: false,
            probe: false,
            probe_unfocused: false,
            delay_ms: 0,
            drop_after: None,
            p1_only: false,
            p2_only: false,
            p3_only: false,
            no_crowd: false,
            legacy_sampler: false,
            fail: false,
            startup_fail: false,
            low_performance_ms: 45.,
            skip_post: None,
            post_create_only: false,
            steve_only: false,
            reflection_first: false,
            probe_hold_ms: 3000,
        }
    }
}
impl Options {
    /// Equals syntax keeps these debug options identical in the app and headless preview.
    pub fn parse(&mut self, arg: &str) -> anyhow::Result<bool> {
        match arg {
            "--scene-packed" => self.packed = true,
            "--scene-full-vertices" => self.packed = false,
            "--scene-chunk-upload" => self.chunk_upload = true,
            "--scene-lazy-targets" => self.lazy_targets = true,
            "--scene-pipelines-only" => self.pipelines_only = true,
            "--scene-memory-probe" => self.probe = true,
            "--scene-probe-unfocused" => self.probe_unfocused = true,
            "--scene-p1-only" => self.p1_only = true,
            "--scene-p2-only" => self.p2_only = true,
            "--scene-p3-only" => self.p3_only = true,
            "--no-crowd" | "--scene-p4a-only" => self.no_crowd = true,
            "--scene-legacy-sampler" => self.legacy_sampler = true,
            "--scene-fail" => self.fail = true,
            "--scene-startup-fail" => self.startup_fail = true,
            "--scene-post-create-only" => self.post_create_only = true,
            "--scene-steve-only" => self.steve_only = true,
            "--scene-player-reflection-first" => self.reflection_first = true,
            _ => {
                if let Some(v) = arg.strip_prefix("--scene-probe-hold-ms=") {
                    self.probe_hold_ms = v.parse()?;
                } else if let Some(v) = arg.strip_prefix("--scene-low-performance-ms=") {
                    self.low_performance_ms = v.parse()?;
                    anyhow::ensure!(
                        self.low_performance_ms.is_finite() && self.low_performance_ms > 0.,
                        "Performance threshold must be finite and positive"
                    );
                } else if let Some(v) = arg.strip_prefix("--scene-skip-post=") {
                    self.skip_post = Some(PostStage::parse(v)?);
                } else if let Some(v) = arg.strip_prefix("--scene-msaa=") {
                    self.samples = v.parse()?;
                    anyhow::ensure!([1, 4].contains(&self.samples), "MSAA must be 1 or 4");
                } else if let Some(v) = arg.strip_prefix("--scene-delay-ms=") {
                    self.delay_ms = v.parse()?;
                } else if let Some(v) = arg.strip_prefix("--scene-drop-after=") {
                    self.drop_after = Some(v.parse()?);
                } else {
                    return Ok(false);
                }
            }
        }
        Ok(true)
    }
    pub fn checkpoint(&self, device: &wgpu::Device, phase: &str) {
        if self.probe {
            snapshot(device, phase);
            std::thread::sleep(std::time::Duration::from_millis(self.probe_hold_ms));
        }
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn owner_defaults_and_diagnostic_overrides() {
        let mut o = Options::default();
        assert_eq!(o.samples, 1);
        assert!(o.packed && !o.chunk_upload && !o.lazy_targets && !o.pipelines_only && !o.probe);
        assert!(o.parse("--scene-msaa=3").is_err());
        assert!(o.parse("--scene-msaa=2").is_err());
        assert!(o.parse("--scene-msaa=4").unwrap());
        assert_eq!(o.samples, 4);
        assert!(o.parse("--scene-full-vertices").unwrap());
        assert!(!o.packed);
    }
    #[test]
    fn diagnostic_failures_and_ab_flags_are_explicit() {
        let mut o = Options::default();
        assert!(
            !o.fail
                && !o.startup_fail
                && !o.steve_only
                && !o.post_create_only
                && !o.reflection_first
                && o.skip_post.is_none()
        );
        assert_eq!(o.low_performance_ms, 45.);
        for value in ["0", "-1", "NaN", "inf"] {
            assert!(
                o.parse(&format!("--scene-low-performance-ms={value}"))
                    .is_err()
            );
        }
        assert!(o.parse("--scene-skip-post=unknown").is_err());
        assert!(o.parse("--scene-skip-post=bloom").unwrap());
        assert_eq!(o.skip_post, Some(PostStage::Bloom));
        assert!(o.parse("--scene-fail").unwrap());
        assert!(o.fail);
    }
}
