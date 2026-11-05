# rdi-renderer

UI-independent wgpu 30.0.1 scene and glass library. It does not create a window or depend on Slint.
Assets remain at their original repository locations; build from the repository checkout.

- `gpu::create(&GpuSettings, Option<&Path>) -> anyhow::Result<GpuContext>` selects an adapter and
  tries the requested compiler with FXC fallback. Settings and the DXC path belong to the caller.
  The context owns instance, adapter, device and queue. The application retains the Windows/Slint
  shutdown lifetime workaround; the library does not leak a context.
- `scene::Renderer` renders the existing fixed-resolution scene on a supplied device/queue.
  `render(FrameInput) -> anyhow::Result<bool>` returns false while a prior frame is in flight.
  `CameraPose { eye, target }` reaches main/reflection uniforms and cloud coordinates.
  `FrameInput::at(seconds)` supplies the original time-based camera; it changes no scene behavior.
- `glass::Renderer::new(device, queue, GridMetrics)` receives layout sizes from the caller.
  `render(&Frame, scene_changed) -> anyhow::Result<Option<wgpu::Texture>>` returns a texture when
  the backdrop is recreated. The caller retains its previous image otherwise and converts textures
  for its UI framework. Targets use Rgba8Unorm.
- Feature `allocation-probe` exposes allocation counters. Install the global allocator in the
  executable, forwarding its feature to this library.

Windows commands from the workspace root:

```powershell
cargo test -p rdi-renderer
cargo run --release -p rdi-renderer --example scene_preview -- evidence/m1/manual 0 15 45 --crowd=50
cargo run --release -p rdi-renderer --example p1_mesh_report
cargo tree -p rdi-renderer -e normal,build
```

The other renderer examples are `p1_precision_audit` and `p3_memory_probe`.
The Slint baseline example stays in the application package.
