# 乳白色玩家队列背景验证

2026-11-18，Windows Rust工具链，DX12，Slint MCP端口55566。

- 默认背景：`parade`，乳白色`#F7F3E8`；`--background valley`保留原山谷。
- 玩家沿底边从左向右行走并循环。人物高84逻辑像素，间距112，速度28像素/秒，脚底距窗口底部8像素。
- 按用户确认沿用现有皮肤列表，仅修改slint原型，未修改后端。
- `cargo test -p rdi-renderer --lib --offline`：66通过，1项原有GPU截图测试跳过。
- `cargo test --bin rdi-slint-proto images::tests::crowd_urls --offline`：1通过。
- `cargo clippy -p rdi-renderer --lib --offline -- -D warnings`：通过。
- `cargo build --release --features slint/live-preview,slint/mcp --offline`：通过。
- 已启动真实菜单，通过Slint MCP截图确认底色、真实皮肤、行走位置和步态变化。
- MCP最大化/还原检查：2560×1440与3840×2090，scale=2；两种尺寸下玩家均保持底边位置，窗口还原成功。
- 本次接口返回50种皮肤，49种加载成功，1种远端返回HTTP502而沿用原有逻辑跳过。
- 程序保持运行，便于继续检查画面。未进行其他平台的运行验收。
