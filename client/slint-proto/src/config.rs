use anyhow::{Context, Result, bail};
pub struct Config {
    pub show_others: bool,
    pub all_playable: bool,
    pub solid: bool,
    /// Slint renderer name; glass needs `femtovg-wgpu`, `software` is for memory comparison only.
    pub renderer: String,
    /// Skip the glass underlay, to measure the renderer with real content but nothing of ours.
    pub no_glass: bool,
    pub no_scene: bool,
    pub gpu_setup: crate::gpu_setup::Mode,
    pub scene: rdi_slint_proto::scene::diagnostics::Options,
    pub scale: Option<f32>,
    pub log: String,
    pub server: String,
}
impl Config {
    pub fn read() -> Result<Self> {
        let mut c = Self {
            show_others: false,
            all_playable: false,
            solid: false,
            renderer: "femtovg-wgpu".into(),
            no_glass: false,
            no_scene: false,
            gpu_setup: crate::gpu_setup::Mode::Manual,
            scene: Default::default(),
            scale: None,
            log: "info".into(),
            server: std::env::var("RDI_PROTO_SERVER")
                .unwrap_or_else(|_| "https://rdi.calebxzhou.cn:65331".into()),
        };
        let mut args = std::env::args().skip(1);
        while let Some(a) = args.next() {
            match a.as_str() {
                "--show-others" => c.show_others = true,
                "--all-playable" => c.all_playable = true,
                "--solid-window" => c.solid = true,
                "--no-glass" => c.no_glass = true,
                "--no-scene" => c.no_scene = true,
                "--renderer" => c.renderer = args.next().context("--renderer需要渲染器名称")?,
                "--scale" => {
                    let s: f32 = args.next().context("--scale需要数值")?.parse()?;
                    anyhow::ensure!(
                        s.is_finite() && (0.5..=4.0).contains(&s),
                        "缩放需在0.5到4之间"
                    );
                    c.scale = Some(s);
                }
                "--log" => c.log = args.next().context("--log需要日志级别")?,
                _ => {
                    if let Some(mode) = a.strip_prefix("--gpu-setup=") {
                        c.gpu_setup = crate::gpu_setup::Mode::parse(mode)?;
                    } else if !c.scene.parse(&a)? {
                        bail!("未知参数：{a}")
                    }
                }
            }
        }
        Ok(c)
    }
    pub fn credentials() -> Result<(String, String)> {
        Ok((
            std::env::var("RDI_PROTO_USR").context("请设置RDI_PROTO_USR和RDI_PROTO_PWD后重试")?,
            std::env::var("RDI_PROTO_PWD").context("请设置RDI_PROTO_USR和RDI_PROTO_PWD后重试")?,
        ))
    }
}
