$ErrorActionPreference = 'Continue'
Set-Location (Split-Path $PSScriptRoot -Parent)
$out = 'evidence/bg-port/startup/validate'
New-Item -ItemType Directory -Force $out | Out-Null
$failed = 0
function Step($name, [scriptblock]$body) {
    & $body *> "$out/$name.log"
    Set-Content "$out/$name.exit" $LASTEXITCODE
    "$name exit=$LASTEXITCODE"
    if ($LASTEXITCODE -ne 0) { $script:failed = 1 }
}
rustfmt --edition 2024 build.rs src/gpu_setup.rs
Step 'fmt' { rustfmt --check --edition 2024 build.rs src/gpu_setup.rs }
cargo fmt --check *> "$out/fmt-all.log"
Step 'clippy' { cargo clippy --all-targets -- -D warnings }
Step 'test' { cargo test }
Step 'release' { cargo build --release --bins --examples }
exit $failed
