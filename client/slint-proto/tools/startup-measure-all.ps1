$ErrorActionPreference = 'Continue'
Set-Location (Split-Path $PSScriptRoot -Parent)
$controls = 'evidence/bg-port/startup/controls'
New-Item -ItemType Directory -Force $controls | Out-Null
Copy-Item 'target/release/rdi-slint-proto.exe', 'target/release/dxcompiler.dll' $controls -Force
$dir = 'evidence/bg-port/startup/measure'
New-Item -ItemType Directory -Force $dir | Out-Null
function Run-Case([string]$Case, [string]$Label) {
    foreach ($attempt in 1..3) {
        $tag = "$Label-r$attempt"
        if (Test-Path "$dir/$tag-measure.exit") { continue }
        & pwsh.exe -NoProfile -ExecutionPolicy Bypass -File tools/startup-measure.ps1 -Case $Case -Tag $tag *> "$dir/$tag-run.log"
        if ($LASTEXITCODE -eq 0 -and (Get-Content "$dir/$tag-measure.exit").Trim() -eq '0') {
            "$Label ok $tag"
            return
        }
        "$Label attempt $attempt rejected"
    }
    "$Label FAILED"
}
foreach ($round in 3, 4) {
    Run-Case 'baseline' "baseline-$round"
    Run-Case 'final' "final-$round"
    Run-Case 'final-fxc' "final-fxc-$round"
}
Run-Case 'final-static' 'final-static-2'
Set-Content "$dir/complete.exit" 0
