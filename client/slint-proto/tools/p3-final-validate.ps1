$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
New-Item -ItemType Directory -Force evidence/bg-port/p3/final-validation | Out-Null
cargo fmt *> evidence/bg-port/p3/final-validation/fmt.log
cargo test --all-features *> evidence/bg-port/p3/final-validation/test.log
Set-Content evidence/bg-port/p3/final-validation/test.exit $LASTEXITCODE
if($LASTEXITCODE -ne 0){throw 'Final tests failed'}
cargo clippy --all-targets --all-features -- -D warnings *> evidence/bg-port/p3/final-validation/clippy.log
Set-Content evidence/bg-port/p3/final-validation/clippy.exit $LASTEXITCODE
if($LASTEXITCODE -ne 0){throw 'Final Clippy failed'}
cargo build --release --bins --examples *> evidence/bg-port/p3/final-validation/release.log
Set-Content evidence/bg-port/p3/final-validation/release.exit $LASTEXITCODE
if($LASTEXITCODE -ne 0){throw 'Final default release failed'}
