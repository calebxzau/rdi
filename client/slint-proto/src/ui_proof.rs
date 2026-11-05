//! U1-only UI risk harness. No credentials or tokens are persisted or logged.
use crate::{AppWindow, ProofRect, ProofRow, api::Client, images};
use anyhow::Result;
use rdi_renderer::glass::{Frame, Panel};
use slint::{ComponentHandle, Model, ModelRc, VecModel, winit_030::WinitWindowAccessor};
use std::{cell::RefCell, rc::Rc, sync::mpsc, time::{Duration, Instant}};
use tokio::task::JoinHandle;

enum Event { Login(Result<Client>), Image(usize, Result<image::RgbaImage>) }
pub struct Proof {
    ui: slint::Weak<AppWindow>,
    runtime: tokio::runtime::Handle,
    base: String,
    api: Option<Client>,
    rows: Rc<VecModel<ProofRow>>,
    tx: mpsc::Sender<Event>,
    rx: mpsc::Receiver<Event>,
    login: Option<JoinHandle<()>>,
    jobs: Vec<JoinHandle<()>>,
    run: Option<Instant>,
    scripted: bool,
    scene_required: bool,
    menu: bool,
    menu_ready: bool,
    scroll_range: Option<(f32, f32)>,
    ready_since: Option<Instant>,
    last_frame: Option<Instant>,
    frame_start: Option<Instant>,
    intervals: [Vec<f64>; 2],
    cpu: [Vec<f64>; 2],
    underlay: [Vec<f64>; 2],
    underlay_ms: Option<f64>,
    loaded_images: usize,
    focus_rejections: usize,
    scene_rejections: usize,
}
pub fn validate_login(user: &str, password: &str) -> Result<()> {
    anyhow::ensure!(!user.trim().is_empty() && !password.trim().is_empty(), "未填写完整");
    Ok(())
}
impl Proof {
    pub fn wire(ui: &AppWindow, runtime: tokio::runtime::Handle, base: String, scripted: bool, scene_required: bool, menu: bool) -> Rc<RefCell<Self>> {
        let (tx, rx) = mpsc::channel();
        let row_count = if menu { 0 } else { 1000 };
        let rows = Rc::new(VecModel::from((0..row_count).map(|i| ProofRow {
            name: format!("朋友的房间{} · Minecraft", i + 1).into(),
            detail: "模组生存 · 中文English · 长名称省略与文字换行测试".into(),
            state: "图片加载中…".into(), ..Default::default()
        }).collect::<Vec<_>>()));
        ui.set_proof_rows(ModelRc::from(rows.clone()));
        let state = Rc::new(RefCell::new(Self { ui: ui.as_weak(), runtime, base, api: None, rows, tx, rx,
            login: None, jobs: vec![], run: None, scripted, scene_required, menu, menu_ready: false, scroll_range: None, ready_since: None, last_frame: None, frame_start: None,
            intervals: Default::default(), cpu: Default::default(), underlay: Default::default(), underlay_ms: None, loaded_images: 0, focus_rejections: 0, scene_rejections: 0 }));
        let weak = Rc::downgrade(&state);
        ui.on_proof_submit(move |u,p| { if let Some(s) = weak.upgrade() { s.borrow_mut().submit(&u,&p); } });
        let weak = Rc::downgrade(&state);
        ui.on_proof_images(move || { if let Some(s) = weak.upgrade() { s.borrow_mut().load_images(); } });
        let weak = Rc::downgrade(&state);
        ui.on_proof_start(move || { if let Some(s) = weak.upgrade() { s.borrow_mut().start(); } });
        if menu { state.borrow_mut().loaded_images = 2; } else { state.borrow_mut().load_images(); }
        state
    }
    fn submit(&mut self, user: &str, password: &str) {
        let Some(ui) = self.ui.upgrade() else { return };
        if ui.get_proof_busy() { return; }
        if let Err(error) = validate_login(user,password) { ui.set_proof_message(error.to_string().into()); return; }
        ui.set_proof_busy(true); ui.set_proof_message("正在登录…".into());
        let (user,password,base,tx) = (user.to_owned(),password.to_owned(),self.base.clone(),self.tx.clone());
        self.login = Some(self.runtime.spawn(async move {
            let result = async { Client::new(base)?.login(&user,&password).await }.await;
            if tx.send(Event::Login(result)).is_err() { tracing::debug!("U1 login receiver closed"); }
        }));
    }
    fn load_images(&mut self) {
        if !self.jobs.is_empty() { return; }
        self.loaded_images = 0;
        // Two distinct shared textures, updated in-place; no per-frame image creation.
        for index in 0..2 {
            for i in (index..1000).step_by(2) {
                if let Some(mut row) = self.rows.row_data(i) { row.state = "图片加载中…".into(); self.rows.set_row_data(i,row); }
            }
            let tx = self.tx.clone();
            self.jobs.push(self.runtime.spawn(async move {
                let result = async {
                    let http = reqwest::Client::builder().connect_timeout(Duration::from_secs(8)).timeout(Duration::from_secs(20)).build()?;
                    let url = format!("https://raw.githubusercontent.com/InventivetalentDev/minecraft-assets/1.21.1/assets/minecraft/textures/entity/player/{}/{}.png", if index % 2 == 0 { "wide" } else { "slim" }, if index % 2 == 0 { "steve" } else { "alex" });
                    let bytes = images::download(&http, &url).await?;
                    Ok(image::imageops::resize(&images::head(&images::decode(&bytes)?.to_rgba8())?,60,60,image::imageops::FilterType::Nearest))
                }.await;
                if tx.send(Event::Image(index,result)).is_err() { tracing::debug!("U1 image receiver closed"); }
            }));
        }
    }
    fn start(&mut self) {
        if self.loaded_images < 2 {
            if let Some(ui) = self.ui.upgrade() { ui.set_proof_message("网络图片尚未加载完成，请稍后重试".into()); }
            return;
        }
        self.run = Some(Instant::now()); self.last_frame = None;
        self.intervals = Default::default(); self.cpu = Default::default(); self.underlay = Default::default();
        self.focus_rejections = 0; self.scene_rejections = 0; self.scroll_range = None;
        if let Some(ui) = self.ui.upgrade() { ui.set_proof_message("检查中：请保持窗口位于前台".into()); }
        tracing::info!("U1 script started: 30s wheel scroll then 10s panel animation");
    }
    pub fn tick(&mut self, scene_live: bool) {
        let Some(ui) = self.ui.upgrade() else { return };
        while let Ok(event) = self.rx.try_recv() {
            match event {
                Event::Login(result) => {
                    ui.set_proof_busy(false); self.login = None;
                    match result {
                        Ok(api) => { self.api = Some(api); ui.set_proof_message("登录成功".into()); tracing::info!("U1 login succeeded"); }
                        Err(error) => {
                            tracing::error!(error = %format!("{error:#}"), "U1 login failed");
                            ui.set_proof_message(format!("登录失败：{error:#}").into());
                        }
                    }
                }
                Event::Image(index,result) => {
                    let (icon,status) = match result {
                        Ok(image) => {
                            self.loaded_images += 1;
                            if self.loaded_images == 2 { tracing::info!("U1 images ready"); }
                            (Some(images::to_slint(&image)), "网络图片已加载")
                        },
                        Err(error) => { tracing::warn!(?error, "U1 network image failed"); (None, "图片加载失败，可重试") }
                    };
                    for i in (index..1000).step_by(2) { if let Some(mut row) = self.rows.row_data(i) {
                        if let Some(icon) = &icon { row.icon = icon.clone(); }
                        row.state = status.into(); self.rows.set_row_data(i,row);
                    }}
                }
            }
        }
        if self.jobs.iter().all(JoinHandle::is_finished) { self.jobs.clear(); }
        if self.menu && !self.menu_ready && !ui.get_loading() && ui.get_error().is_empty() {
            self.menu_ready = true;
            tracing::info!("Menu proof ready");
        }
        if self.scripted && self.run.is_none() && (scene_live || !self.scene_required) && self.loaded_images == 2 {
            let mut focused = false;
            ui.window().with_winit_window(|w| { focused = w.has_focus(); });
            if focused {
                let ready = self.ready_since.get_or_insert_with(Instant::now);
                if ready.elapsed() >= Duration::from_secs(5) { self.scripted = false; self.start(); }
            } else { self.ready_since = None; }
        }
        let Some(run) = self.run else { return };
        let seconds = run.elapsed().as_secs_f32();
        if seconds < 30. {
            // Dispatch a wheel input to the actual ListView, not a replacement list model.
            let size = ui.window().size();
            let scale = ui.window().scale_factor();
            ui.window().dispatch_event(slint::platform::WindowEvent::PointerScrolled {
                position: slint::LogicalPosition::new(size.width as f32 / scale * 0.75, size.height as f32 / scale * 0.6),
                delta_x: 0., delta_y: if (seconds / 5.) as i32 % 2 == 0 { -24. } else { 24. },
            });
            if self.menu {
                let scroll = ui.get_host_scroll();
                let (low, high) = self.scroll_range.unwrap_or((scroll, scroll));
                self.scroll_range = Some((low.min(scroll), high.max(scroll)));
            }
        } else if !self.menu && seconds < 40. { ui.set_proof_panel_open((seconds * 1.4) as i32 % 2 == 0); }
        else {
            self.run = None; ui.set_proof_panel_open(false);
            let result = serde_json::json!({
                "network_images": self.loaded_images,
                "list": distribution(&self.intervals[0]), "panel": distribution(&self.intervals[1]),
                "list_cpu": distribution(&self.cpu[0]), "panel_cpu": distribution(&self.cpu[1]),
                "list_underlay": distribution(&self.underlay[0]), "panel_underlay": distribution(&self.underlay[1]),
                "list_pacing": pacing(&self.intervals[0]), "panel_pacing": pacing(&self.intervals[1]),
                "scene_required": self.scene_required, "menu": self.menu,
                "menu_scroll_range": self.scroll_range.map(|(low, high)| high - low),
                "unfocused_frames":self.focus_rejections,"scene_unavailable_frames":self.scene_rejections,
                "valid":self.loaded_images == 2 && self.focus_rejections == 0 && self.scene_rejections == 0 && !self.intervals[0].is_empty() && (self.menu || !self.intervals[1].is_empty())
            });
            tracing::info!(data=%result,"U1 measurements");
            ui.set_proof_message("检查完成，结果已写入日志".into());
        }
    }
    pub fn heartbeat(&self) {
        if let Some(ui) = self.ui.upgrade() {
            tracing::info!(data=%serde_json::json!({"scroll":ui.get_proof_scroll(),"progress":ui.get_proof_progress(),
                "panel":[ui.get_proof_panel().x,ui.get_proof_panel().y,ui.get_proof_panel().w,ui.get_proof_panel().h],
                "scale":ui.window().scale_factor()}),"U1 layout");
        }
    }
    pub fn before_frame(&mut self) { self.frame_start = Some(Instant::now()); self.underlay_ms = None; }
    /// Marks the end of scene and glass work, so the rest of the frame is Slint's own.
    pub fn underlay_done(&mut self) {
        self.underlay_ms = self.frame_start.map(|start| start.elapsed().as_secs_f64()*1000.);
    }
    pub fn after_frame(&mut self, scene_live: bool) {
        let scene_live = scene_live || !self.scene_required;
        let Some(run) = self.run else { return };
        let Some(ui) = self.ui.upgrade() else { return };
        let now = Instant::now();
        let mut focused = false;
        ui.window().with_winit_window(|w| { focused = w.has_focus(); });
        if !focused { self.focus_rejections += 1; }
        if !scene_live { self.scene_rejections += 1; }
        let index = usize::from(run.elapsed() >= Duration::from_secs(30));
        if index > 1 { return; }
        if focused && scene_live {
            if let Some(last) = self.last_frame { self.intervals[index].push(now.duration_since(last).as_secs_f64()*1000.); }
            if let Some(start) = self.frame_start { self.cpu[index].push(now.duration_since(start).as_secs_f64()*1000.); }
            if let Some(underlay) = self.underlay_ms { self.underlay[index].push(underlay); }
        }
        self.last_frame = Some(now);
    }
}
fn distribution(values: &[f64]) -> serde_json::Value {
    let mut sorted = values.to_vec(); sorted.sort_by(f64::total_cmp);
    if sorted.is_empty() { return serde_json::json!({"count":0}); }
    let percentile = |p: f64| sorted[((sorted.len()-1) as f64*p).ceil() as usize];
    serde_json::json!({"count":sorted.len(),"p50_ms":percentile(0.50),"p95_ms":percentile(0.95),"p99_ms":percentile(0.99),"worst_ms":sorted[sorted.len()-1]})
}
/// The scene draws one frame before it can hold, so a screen entered first still has a picture.
pub fn scene_held(frozen: bool, scene_bound: bool) -> bool { frozen && scene_bound }
/// A long frame is one that took over 1.5 refresh intervals at 60Hz, so a vsync was missed.
const LONG_FRAME_MS: f64 = 25.;
fn pacing(values: &[f64]) -> serde_json::Value {
    if values.is_empty() { return serde_json::json!({"count":0}); }
    let long = values.iter().filter(|value| **value > LONG_FRAME_MS).count();
    serde_json::json!({"count":values.len(),"long_frames":long,"long_frame_pct":long as f64*100./values.len() as f64,
        "delivered_fps":values.len() as f64*1000./values.iter().sum::<f64>()})
}
impl Drop for Proof { fn drop(&mut self) { if let Some(job) = &self.login { job.abort(); } for job in &self.jobs { job.abort(); } } }
fn panel(rect: ProofRect, tint: [f32;4]) -> Panel {
    Panel { rect: [rect.x,rect.y,rect.w,rect.h], corner_radius:rect.radius, tint,opacity:rect.alpha }
}
pub fn frame(ui: &AppWindow) -> Frame {
    let size = ui.window().size();
    Frame { width:size.width as i32,height:size.height as i32,scale:ui.window().scale_factor(), grids:vec![],pills:vec![],
        panels:vec![panel(ui.get_proof_form(),[1.,1.,1.,0.55]),panel(ui.get_proof_list(),[1.,1.,1.,0.32]),
            panel(ui.get_proof_panel(),[0.98,0.97,1.,0.35]),panel(ui.get_proof_modal(),[1.,1.,1.,0.65])] }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test] fn login_validation() { assert!(validate_login(" ","pass").is_err()); assert!(validate_login("123","").is_err()); assert!(validate_login("123","pass").is_ok()); }
    #[test] fn percentile_definition() { let d=distribution(&[1.,3.,2.,100.]); assert_eq!(d["p50_ms"],3.); assert_eq!(d["p99_ms"],100.); assert_eq!(distribution(&[])["count"],0); }
    #[test] fn scene_draws_once_before_holding() {
        assert!(!scene_held(true,false)); assert!(scene_held(true,true)); assert!(!scene_held(false,true));
    }
    #[test] fn pacing_counts_long_frames() {
        let p=pacing(&[20.,20.,20.,40.]);
        assert_eq!(p["long_frames"],1); assert_eq!(p["long_frame_pct"],25.); assert_eq!(p["delivered_fps"],40.);
        assert_eq!(pacing(&[25.])["long_frames"],0); assert_eq!(pacing(&[])["count"],0);
    }
}
