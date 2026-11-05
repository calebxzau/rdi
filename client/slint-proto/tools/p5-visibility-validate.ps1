$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
New-Item -ItemType Directory -Force evidence/bg-port/p5/validation3 | Out-Null
cargo fmt *> evidence/bg-port/p5/validation3/fmt.log
cargo test --all-features *> evidence/bg-port/p5/validation3/test.log
Set-Content evidence/bg-port/p5/validation3/test.exit $LASTEXITCODE
if($LASTEXITCODE -ne 0){throw 'P5 tests failed'}
cargo clippy --all-targets --all-features -- -D warnings *> evidence/bg-port/p5/validation3/clippy.log
Set-Content evidence/bg-port/p5/validation3/clippy.exit $LASTEXITCODE
if($LASTEXITCODE -ne 0){throw 'P5 Clippy failed'}
cargo build --release --bins --examples *> evidence/bg-port/p5/validation3/release.log
Set-Content evidence/bg-port/p5/validation3/release.exit $LASTEXITCODE
if($LASTEXITCODE -ne 0){throw 'P5 release failed'}
Copy-Item target/release/rdi-slint-proto.exe evidence/bg-port/p5/controls/p5-default.exe
cargo build --release --features allocation-probe --bin rdi-slint-proto *> evidence/bg-port/p5/validation3/probe-build.log
Set-Content evidence/bg-port/p5/validation3/probe-build.exit $LASTEXITCODE
if($LASTEXITCODE -ne 0){throw 'P5 probe build failed'}
Copy-Item target/release/rdi-slint-proto.exe evidence/bg-port/p5/controls/p5-probe.exe
Copy-Item evidence/bg-port/p5/controls/p5-default.exe target/release/rdi-slint-proto.exe
Set-Content evidence/bg-port/p5/validation-complete.exit 0
