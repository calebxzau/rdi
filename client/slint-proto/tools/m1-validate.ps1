$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$out = 'evidence/m1/validation'
New-Item -ItemType Directory -Force $out | Out-Null
foreach ($features in @('', '--all-features')) {
    $tag = if ($features) { 'all-features' } else { 'default' }
    [string[]]$extra = @()
    if ($features) { $extra = @($features) }
    & cargo test --release --workspace @extra *> "$out/tests-$tag.log"
    if ($LASTEXITCODE) { throw 'tests failed' }
    & cargo clippy --workspace --all-targets @extra -- -D warnings *> "$out/clippy-$tag.log"
    if ($LASTEXITCODE) { throw 'clippy failed' }
}
& cargo tree -p rdi-renderer -e normal,build > "$out/renderer-tree.txt"
if ($LASTEXITCODE) { throw 'dependency tree failed' }
if (Select-String -Path "$out/renderer-tree.txt" -Pattern '\b(?:slint|i-slint-[\w-]+) v[0-9]') { throw 'renderer depends on Slint' }
& cargo tree --workspace -i wgpu > "$out/wgpu-tree.txt"
if ($LASTEXITCODE) { throw 'wgpu version must resolve unambiguously' }
Set-Content "$out/complete.exit" 0
