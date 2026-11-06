$ErrorActionPreference = 'Stop'
Set-Location "$PSScriptRoot/.."
$out = 'evidence/ui-port/u3'
Set-Content "$out/validation.exit" 'running'
& cargo test --workspace --lib --bins *> "$out/tests.log"
if ($LASTEXITCODE) { Set-Content "$out/validation.exit" 'failed-tests'; throw 'Rust tests failed' }
& cargo clippy --workspace --all-targets -- -D warnings *> "$out/clippy.log"
if ($LASTEXITCODE) { Set-Content "$out/validation.exit" 'failed-clippy'; throw 'Clippy failed' }
& cargo build --release *> "$out/build.log"
if ($LASTEXITCODE) { Set-Content "$out/validation.exit" 'failed-build'; throw 'Release build failed' }
Set-Content "$out/validation.exit" 0
