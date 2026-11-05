$ErrorActionPreference='Continue'
$PSNativeCommandUseErrorActionPreference=$false
Set-Location (Split-Path $PSScriptRoot -Parent)
cargo fmt *> evidence/bg-port/p1/fmt.log
cargo test *> evidence/bg-port/p1/test.log
Set-Content evidence/bg-port/p1/test.exit $LASTEXITCODE
cargo clippy --all-targets -- -D warnings *> evidence/bg-port/p1/clippy.log
Set-Content evidence/bg-port/p1/clippy.exit $LASTEXITCODE
cargo build --release --bins --examples *> evidence/bg-port/p1/release.log
Set-Content evidence/bg-port/p1/release.exit $LASTEXITCODE
