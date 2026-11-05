$ErrorActionPreference='Continue'
$PSNativeCommandUseErrorActionPreference=$false
Set-Location (Split-Path $PSScriptRoot -Parent)
cargo fmt *> evidence/bg-port/p1/full/fmt.log
cargo test *> evidence/bg-port/p1/full/test.log
Set-Content evidence/bg-port/p1/full/test.exit $LASTEXITCODE
cargo clippy --all-targets -- -D warnings *> evidence/bg-port/p1/full/clippy.log
Set-Content evidence/bg-port/p1/full/clippy.exit $LASTEXITCODE
cargo build --release --bins --examples *> evidence/bg-port/p1/full/release.log
Set-Content evidence/bg-port/p1/full/release.exit $LASTEXITCODE
