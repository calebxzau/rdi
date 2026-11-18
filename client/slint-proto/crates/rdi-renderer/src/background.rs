//! Selectable backgrounds behind the shared glass and Slint UI.
use crate::{
    parade,
    scene::{
        self,
        diagnostics::Options,
        player::{Appearance, Skin},
    },
};
use anyhow::Result;
use std::sync::mpsc;

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum Kind {
    #[default]
    Parade,
    Valley,
}
impl Kind {
    pub fn parse(value: &str) -> Result<Self> {
        match value {
            "parade" => Ok(Self::Parade),
            "valley" => Ok(Self::Valley),
            _ => anyhow::bail!("未知背景：{value}，可选parade或valley"),
        }
    }
    pub fn fallback_color(self) -> Option<[u8; 4]> {
        match self {
            Self::Parade => Some(parade::COLOR),
            Self::Valley => None,
        }
    }
}
pub enum Renderer {
    Parade(Box<parade::Renderer>),
    Valley(Box<scene::Renderer>),
}
impl Renderer {
    pub fn new(
        device: wgpu::Device,
        queue: wgpu::Queue,
        kind: Kind,
        options: Options,
    ) -> Result<Self> {
        Ok(match kind {
            Kind::Parade => Self::Parade(Box::new(parade::Renderer::new(device, queue, options)?)),
            Kind::Valley => Self::Valley(Box::new(scene::Renderer::with_options(
                device, queue, options,
            )?)),
        })
    }
    pub fn set_player_appearance(&mut self, appearance: Appearance) -> Result<()> {
        match self {
            Self::Parade(r) => r.set_player_appearance(appearance),
            Self::Valley(r) => r.set_player_appearance(appearance),
        }
    }
    pub fn set_crowd_skin(&mut self, id: &str, skin: Skin) -> Result<()> {
        match self {
            Self::Parade(r) => r.set_crowd_skin(id, skin),
            Self::Valley(r) => r.set_crowd_skin(id, skin),
        }
    }
    pub fn pause(&mut self) {
        match self {
            Self::Parade(r) => r.pause(),
            Self::Valley(r) => r.pause(),
        }
    }
    pub fn output(&self) -> &wgpu::Texture {
        match self {
            Self::Parade(r) => r.output(),
            Self::Valley(r) => r.output(),
        }
    }
    /// (frame submitted, source texture changed). Parade follows the actual framebuffer size
    /// so cover-cropping the glass source never cuts off the players at another aspect ratio.
    pub fn render(
        &mut self,
        seconds: f64,
        width: u32,
        height: u32,
        scale: f32,
    ) -> Result<(bool, bool)> {
        match self {
            Self::Parade(r) => r.render(seconds, width, height, scale),
            Self::Valley(r) => r
                .render(scene::FrameInput::at(seconds))
                .map(|changed| (changed, false)),
        }
    }
}
pub fn spawn(
    device: wgpu::Device,
    queue: wgpu::Queue,
    first_frame: mpsc::Receiver<()>,
    kind: Kind,
    options: Options,
) -> Result<mpsc::Receiver<Result<Renderer>>> {
    let (tx, rx) = mpsc::sync_channel(1);
    std::thread::Builder::new()
        .name("background-loader".into())
        .spawn(move || {
            if first_frame.recv().is_err() {
                return;
            }
            let start = std::time::Instant::now();
            let result = Renderer::new(device, queue, kind, options);
            if let Err(error) = &result {
                tracing::error!(error = %format!("{error:#}"), ?kind, "Background loader failed");
            }
            tracing::info!(
                ?kind,
                elapsed_ms = start.elapsed().as_millis(),
                "Background loader finished"
            );
            let _ = tx.send(result);
        })?;
    Ok(rx)
}
