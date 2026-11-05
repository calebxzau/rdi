$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
cargo fmt *> evidence/bg-port/p3/cpu-fmt.log
cargo clippy --all-targets -- -D warnings *> evidence/bg-port/p3/cpu-clippy.log
Set-Content evidence/bg-port/p3/cpu-clippy.exit $LASTEXITCODE
if($LASTEXITCODE -ne 0){throw 'Diagnostic Clippy failed'}
cargo build --release --example p3_memory_probe *> evidence/bg-port/p3/cpu-build.log
Set-Content evidence/bg-port/p3/cpu-build.exit $LASTEXITCODE
if($LASTEXITCODE -ne 0){throw 'Diagnostic build failed'}
& pwsh.exe -NoProfile -File tools/p3-cpu-probe.ps1
if($LASTEXITCODE -ne 0){throw 'CPU attribution failed'}
