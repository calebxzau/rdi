$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot)
$evidence = 'evidence/ui-port/u4a'
foreach ($name in @('rust','clippy','release')) { if (Test-Path "$evidence/$name.log") { Copy-Item "$evidence/$name.log" "$evidence/$name-$([DateTime]::UtcNow.ToString('yyyyMMddHHmmss')).log" } }
rustfmt --edition 2024 src/packs.rs
if ($LASTEXITCODE -ne 0) { throw 'Formatting packs.rs failed' }
cargo test --workspace --lib --bins *> "$evidence/rust.log"
$testCode = $LASTEXITCODE
if ($testCode -eq 0) {
    cargo clippy --workspace --all-targets -- -D warnings *> "$evidence/clippy.log"
    $clippyCode = $LASTEXITCODE
    cargo build --release *> "$evidence/release.log"
    $releaseCode = $LASTEXITCODE
}
@{tests=$testCode;clippy=$clippyCode;release=$releaseCode} | ConvertTo-Json | Set-Content "$evidence/rust.exit.json"
