$ErrorActionPreference='Continue'
$PSNativeCommandUseErrorActionPreference=$false
Set-Location (Split-Path $PSScriptRoot -Parent)
cargo fmt *> evidence/bg-port/p0.5/fmt.log
cargo test *> evidence/bg-port/p0.5/test.log
Set-Content evidence/bg-port/p0.5/test.exit $LASTEXITCODE
cargo clippy --all-targets -- -D warnings *> evidence/bg-port/p0.5/clippy.log
Set-Content evidence/bg-port/p0.5/clippy.exit $LASTEXITCODE
cargo build --release --example scene_preview *> evidence/bg-port/p0.5/preview-release.log
Set-Content evidence/bg-port/p0.5/preview-release.exit $LASTEXITCODE
if($LASTEXITCODE -eq 0){
 & ./target/release/examples/scene_preview.exe evidence/bg-port/p0.5/preview-pipelines-only --scene-pipelines-only *> evidence/bg-port/p0.5/preview-pipelines-only.log
 Set-Content evidence/bg-port/p0.5/preview-pipelines-only.exit $LASTEXITCODE
}
