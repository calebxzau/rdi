Set-Location (Split-Path $PSScriptRoot -Parent)
cargo fmt *> evidence/fmt.log
cargo test *> evidence/test.log
Set-Content evidence/test.exit $LASTEXITCODE
cargo clippy -- -D warnings *> evidence/clippy.log
Set-Content evidence/clippy.exit $LASTEXITCODE
cargo build --release *> evidence/release.log
Set-Content evidence/release.exit $LASTEXITCODE
