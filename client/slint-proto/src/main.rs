mod anim;
mod api;
mod app;
mod config;
use rdi_renderer::glass;
mod gpu_setup;
mod hosts;
mod images;
use rdi_slint_proto::scene;
mod runtime_probe;
mod window;
use slint::ComponentHandle;
use std::{
    rc::Rc,
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
    },
    time::{Duration, Instant},
};
slint::include_modules!();
#[cfg(feature = "allocation-probe")]
#[global_allocator]
static ALLOCATION_PROBE: rdi_slint_proto::allocation_probe::Counting =
    rdi_slint_proto::allocation_probe::Counting;
fn main() -> anyhow::Result<()> {
    let started = Instant::now();
    let config = config::Config::read()?;
    tracing_subscriber::fmt()
        .with_env_filter(tracing_subscriber::EnvFilter::new(&config.log))
        .with_target(false)
        .init();
    let log_stage = |stage: &str| {
        tracing::info!(
            stage,
            elapsed_ms = started.elapsed().as_millis(),
            "Startup stage"
        );
    };
    let mut selector = slint::BackendSelector::new()
        .backend_name("winit".into())
        .renderer_name(config.renderer.clone());
    if config.renderer == "femtovg-wgpu" {
        selector = selector.require_wgpu_30(gpu_setup::configuration(config.gpu_setup));
        log_stage("gpu-configured");
    }
    selector.select()?;
    log_stage("backend-selected");
    let ui = AppWindow::new()?;
    log_stage("window-created");
    if let Some(scale) = config.scale {
        ui.window()
            .dispatch_event(slint::platform::WindowEvent::ScaleFactorChanged {
                scale_factor: scale,
            });
    }
    if config.solid {
        ui.window().with_winit_window(|w| w.set_transparent(false));
    }
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .max_blocking_threads(2)
        .enable_all()
        .build()?;
    let paused = Arc::new(AtomicBool::new(false));
    window::wire(
        &ui,
        paused.clone(),
        config.scene.probe && config.scene.probe_unfocused,
    );
    let scene_options = config.scene;
    scene::diagnostics::enable(scene_options.probe);
    let mut last_snapshot = Instant::now();
    let mut scene_live_start = None;
    let no_glass = config.no_glass;
    let no_scene = config.no_scene || no_glass;
    let scene_enabled = Arc::new(AtomicBool::new(!no_scene));
    let render_enabled = scene_enabled.clone();
    let render_paused = paused.clone();
    let clock = Rc::new(std::cell::RefCell::new(scene::perf::Clock::new()));
    let render_clock = clock.clone();
    let stats = Rc::new(std::cell::RefCell::new(runtime_probe::Stats::default()));
    let render_stats = stats.clone();
    let mut terminal_reported = false;
    let mut scene_loader = None;
    let mut scene_start = None;
    let mut scene_renderer = None;
    let mut scene_bound = false;
    let mut scene_epoch = 0;
    let app = app::App::new(&ui, config, runtime.handle().clone(), paused.clone())?;
    log_stage("app-created");
    let weak = ui.as_weak();
    let state = Rc::downgrade(&app);
    let mut renderer = None;
    let mut failed = false;
    let mut first = true;
    ui.window()
        .set_rendering_notifier(move |stage, api| match stage {
            slint::RenderingState::RenderingSetup if no_glass => failed = true,
            slint::RenderingState::RenderingSetup => {
                if let slint::GraphicsAPI::WGPU30 { device, queue, .. } = api {
                    tracing::info!(
                        stage = "rendering-setup",
                        elapsed_ms = started.elapsed().as_millis(),
                        "Startup stage"
                    );
                    match glass::Renderer::new(device.clone(), queue.clone(), hosts::grid_metrics()) {
                        Ok(r) => {
                            tracing::info!(
                                stage = "glass-ready",
                                elapsed_ms = started.elapsed().as_millis(),
                                "Startup stage"
                            );
                            renderer = Some(r);
                            scene::diagnostics::snapshot(device, "glass-pipelines-source-ready");
                            if render_enabled.load(Ordering::Relaxed) {
                                let (start, wait) = std::sync::mpsc::sync_channel(1);
                                scene_start = Some(start);
                                match scene::loader::spawn(device.clone(), queue.clone(), wait, scene_options) {
                                    Ok(loader) => {scene_loader = Some(loader);render_stats.borrow_mut().loading=true;},
                                    Err(error) => { tracing::error!(error=%format!("{error:#}"), "Scene worker failed to start"); render_enabled.store(false, Ordering::Relaxed); }
                                }
                            }
                        },
                        Err(error) => {
                            tracing::error!(error=?error,"Glass renderer setup failed");
                            failed = true;
                            if let Some(ui) = weak.upgrade() {
                                ui.set_error(format!("玻璃绘制初始化失败：{error:#}").into());
                            }
                        }
                    }
                } else {
                    tracing::error!("wgpu is unavailable");
                    failed = true;
                }
            }
            slint::RenderingState::BeforeRendering if !failed => {
                if let (Some(r), Some(ui), Some(app)) =
                    (&mut renderer, weak.upgrade(), state.upgrade())
                {
                    let frame = app.borrow().frame(&ui);
                    if let Some(loader) = &scene_loader {
                        match loader.try_recv() {
                            Ok(Ok(scene)) => { scene_renderer = Some(scene); scene_loader = None; }
                            Ok(Err(error)) => { tracing::error!(error=%format!("{error:#}"), "Scene unavailable; static fallback"); scene_loader = None; render_enabled.store(false, Ordering::Relaxed); }
                            Err(std::sync::mpsc::TryRecvError::Disconnected) => { tracing::error!("Scene worker disconnected; static fallback"); scene_loader = None; render_enabled.store(false, Ordering::Relaxed); }
                            Err(std::sync::mpsc::TryRecvError::Empty) => {}
                        }
                    }
                    if scene_options.drop_after.is_some_and(|seconds| scene_live_start.is_some_and(|t: Instant| t.elapsed().as_secs() >= seconds)) {
                        r.set_scene(None); scene_renderer = None; scene_bound = false; scene_live_start = None;
                        render_enabled.store(false, Ordering::Relaxed);
                        tracing::warn!("Diagnostic terminal scene fallback");
                        scene::diagnostics::snapshot(r.device(), "terminal-drop");
                    }
                    if !render_enabled.load(Ordering::Relaxed) {
                        app.borrow_mut().stop_player_appearance();
                        scene_loader=None;scene_start=None;
                    }
                    let mut changed = false;
                    let epoch = render_clock.borrow().generation();
                    if epoch != scene_epoch {
                        if let Some(scene) = &mut scene_renderer { scene.pause(); }
                        scene_epoch = epoch;
                    }
                    if render_paused.load(Ordering::Relaxed) && let Some(scene) = &mut scene_renderer { scene.pause(); }
                    if !render_paused.load(Ordering::Relaxed) && let Some(scene) = &mut scene_renderer {
                        let appearance=app.borrow_mut().take_player_appearance();
                        let crowd_skins=app.borrow_mut().take_crowd_skins();
                        let result=(|| {
                            if let Some(appearance)=appearance { scene.set_player_appearance(appearance)?; }
                            for (id,skin) in crowd_skins { scene.set_crowd_skin(&id,skin)?; }
                            scene.render(scene::FrameInput::at(render_clock.borrow().elapsed()))
                        })();
                        match result {
                            Ok(rendered) => {
                                changed = rendered;
                                if rendered {render_stats.borrow_mut().scene_frames+=1;}
                                if rendered && !scene_bound {
                                    r.set_scene(Some(scene.output())); scene_bound = true; scene_live_start = Some(Instant::now());
                                    tracing::info!(elapsed_ms=started.elapsed().as_millis(), "First live scene frame");
                                }
                            }
                            Err(error) => {
                                tracing::error!(error=%format!("{error:#}"), "Scene stopped; terminal static fallback");
                                r.set_scene(None); scene_renderer = None; scene_bound = false;
                                render_enabled.store(false, Ordering::Relaxed);
                            }
                        }
                    }
                    if !render_enabled.load(Ordering::Relaxed) {
                        app.borrow_mut().stop_player_appearance();
                        scene_loader=None;scene_start=None;
                        if !terminal_reported && !no_scene {
                            tracing::warn!("Terminal scene state; resources detached and no reload permitted");
                            terminal_reported=true;
                        }
                    }
                    {let mut stats=render_stats.borrow_mut();stats.bound=scene_bound;stats.loading=scene_loader.is_some();}
                    let backdrop = r.render(&frame, changed).and_then(|texture| {
                        texture.map(|texture| {
                            slint::Image::try_from(texture)
                                .map_err(|e| anyhow::anyhow!("背景纹理无法交给Slint：{e}"))
                        }).transpose()
                    });
                    match backdrop {
                        Ok(Some(backdrop)) => ui.set_backdrop(backdrop),
                        Ok(None) => {}
                        Err(error) => {
                            tracing::error!(error=?error,"Glass frame failed");
                            failed = true;
                            ui.set_error(format!("玻璃绘制失败：{error:#}").into());
                        }
                    }
                    if scene_options.probe && last_snapshot.elapsed().as_secs() >= 5 {
                        r.device().poll(slint::wgpu_30::wgpu::PollType::Poll).ok();
                        scene::diagnostics::snapshot(r.device(), if scene_bound {"app-live-glass"} else {"app-static"});
                        last_snapshot = Instant::now();
                    }
                }
            }
            slint::RenderingState::AfterRendering => {
                render_stats.borrow_mut().ui_frames+=1;
                if let Some(start) = scene_start.take() && start.send(()).is_err() {
                    tracing::error!("Scene worker disconnected before initialization");
                    render_enabled.store(false, Ordering::Relaxed);
                }
                if first {
                    tracing::info!(
                        elapsed_ms = started.elapsed().as_millis(),
                        scale = weak.upgrade().map(|u| u.window().scale_factor()),
                        "First frame"
                    );
                    first = false;
                }
            }
            slint::RenderingState::RenderingTeardown => {
                renderer = None;
                scene_renderer = None;
                scene_loader = None;
                scene_start = None;
                render_enabled.store(false, Ordering::Relaxed);
            }
            _ => {}
        })
        .or_else(|e| match e {
            // The software renderer is only used for memory comparison; it runs without glass.
            slint::SetRenderingNotifierError::Unsupported => {
                tracing::warn!("Renderer has no rendering notifier; glass is disabled");
                scene_enabled.store(false, Ordering::Relaxed);
                Ok(())
            }
            e => Err(anyhow::anyhow!("rendering notifier: {e:?}")),
        })?;
    let timer = slint::Timer::default();
    let state = Rc::downgrade(&app);
    let mut last = Instant::now();
    let timer_ui = ui.as_weak();
    let mut last_probe_redraw = Instant::now();
    let mut control = runtime_probe::Control::new();
    timer.start(
        slint::TimerMode::Repeated,
        Duration::from_millis(16),
        move || {
            let now = Instant::now();
            let dt = now.duration_since(last).as_secs_f32();
            last = now;
            let active = scene_enabled.load(Ordering::Relaxed) && !paused.load(Ordering::Relaxed);
            if clock.borrow_mut().tick(now, active)
                && let Some(ui) = timer_ui.upgrade()
            {
                ui.window().request_redraw();
            }
            if scene_options.probe && last_probe_redraw.elapsed().as_secs() >= 1 {
                if let Some(ui) = timer_ui.upgrade() {
                    control.poll(&ui);
                    runtime_probe::heartbeat(
                        &ui,
                        &stats.borrow(),
                        clock.borrow().elapsed(),
                        active,
                        scene_enabled.load(Ordering::Relaxed),
                    );
                    ui.window().request_redraw();
                }
                last_probe_redraw = now;
            }
            if let Some(app) = state.upgrade() {
                app.borrow_mut().tick(dt);
            }
        },
    );
    ui.run()?;
    timer.stop();
    drop(app);
    runtime.shutdown_timeout(Duration::from_secs(2));
    Ok(())
}
use slint::winit_030::WinitWindowAccessor;
