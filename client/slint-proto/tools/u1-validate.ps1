$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$out = 'evidence/ui-port/u1'
New-Item -ItemType Directory -Force $out | Out-Null
& cargo test --workspace --all-features *> "$out/tests.log"
if ($LASTEXITCODE) { throw 'tests failed' }
& cargo clippy --workspace --all-targets --all-features -- -D warnings *> "$out/clippy.log"
if ($LASTEXITCODE) { throw 'clippy failed' }
& cargo build --release --workspace --bins --examples *> "$out/build.log"
if ($LASTEXITCODE) { throw 'build failed' }
Set-Content "$out/validation.exit" 0
