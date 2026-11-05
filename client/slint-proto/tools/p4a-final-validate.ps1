$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
New-Item -ItemType Directory -Force evidence/bg-port/p4a/final-validation | Out-Null
cargo fmt *> evidence/bg-port/p4a/final-validation/fmt.log
cargo test --all-features *> evidence/bg-port/p4a/final-validation/test.log
Set-Content evidence/bg-port/p4a/final-validation/test.exit $LASTEXITCODE
if($LASTEXITCODE -ne 0){throw 'Player tests failed'}
cargo clippy --all-targets --all-features -- -D warnings *> evidence/bg-port/p4a/final-validation/clippy.log
Set-Content evidence/bg-port/p4a/final-validation/clippy.exit $LASTEXITCODE
if($LASTEXITCODE -ne 0){throw 'Player Clippy failed'}
cargo build --release --bins --examples *> evidence/bg-port/p4a/final-validation/release.log
Set-Content evidence/bg-port/p4a/final-validation/release.exit $LASTEXITCODE
if($LASTEXITCODE -ne 0){throw 'Release build failed'}
