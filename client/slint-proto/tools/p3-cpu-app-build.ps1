$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
New-Item -ItemType Directory -Force evidence/bg-port/p3/cpu-app | Out-Null
cargo fmt *> evidence/bg-port/p3/cpu-app/fmt.log
cargo clippy --all-targets --features allocation-probe -- -D warnings *> evidence/bg-port/p3/cpu-app/clippy.log
Set-Content evidence/bg-port/p3/cpu-app/clippy.exit $LASTEXITCODE
if($LASTEXITCODE -ne 0){throw 'Allocation-probe Clippy failed'}
cargo build --release --features allocation-probe --bin rdi-slint-proto *> evidence/bg-port/p3/cpu-app/build.log
Set-Content evidence/bg-port/p3/cpu-app/build.exit $LASTEXITCODE
if($LASTEXITCODE -ne 0){throw 'Allocation-probe build failed'}
Copy-Item target/release/rdi-slint-proto.exe evidence/bg-port/p3/cpu-app/tracked.exe
& javac -d evidence/bg-port/p3/launcher-classes tools/P3CpuMeasureLauncher.java
if($LASTEXITCODE -ne 0){throw 'CPU launcher compile failed'}
Set-Content evidence/bg-port/p3/cpu-app/launcher-manifest.mf "Premain-Class: P3CpuMeasureLauncher`n" -Encoding ascii
& jar cfm evidence/bg-port/p3/cpu-app/launcher.jar evidence/bg-port/p3/cpu-app/launcher-manifest.mf -C evidence/bg-port/p3/launcher-classes .
if($LASTEXITCODE -ne 0){throw 'CPU launcher packaging failed'}
Set-Content evidence/bg-port/p3/cpu-app/ready.exit 0
