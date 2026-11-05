$ErrorActionPreference='Stop'
$PSNativeCommandUseErrorActionPreference=$false
Set-Location (Split-Path $PSScriptRoot -Parent)
New-Item -ItemType Directory -Force evidence/bg-port/p3 | Out-Null
cargo fmt *> evidence/bg-port/p3/fmt.log
if($LASTEXITCODE -ne 0){throw 'Rust formatting failed'}
cargo test *> evidence/bg-port/p3/test.log
Set-Content evidence/bg-port/p3/test.exit $LASTEXITCODE
if($LASTEXITCODE -ne 0){throw 'Rust tests failed'}
cargo clippy --all-targets -- -D warnings *> evidence/bg-port/p3/clippy.log
Set-Content evidence/bg-port/p3/clippy.exit $LASTEXITCODE
if($LASTEXITCODE -ne 0){throw 'Clippy failed'}
cargo build --release --bins --examples *> evidence/bg-port/p3/release.log
Set-Content evidence/bg-port/p3/release.exit $LASTEXITCODE
if($LASTEXITCODE -ne 0){throw 'Release build failed'}
