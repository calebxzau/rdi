$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$root='evidence/bg-port/p1/visual'
if ((Get-Content "$root/release.exit").Trim() -ne '0') { throw 'Release build did not pass' }
& ./target/release/examples/scene_preview.exe "$root/final" 0 15 45 --capture-hdr *> "$root/final-preview.log"
Set-Content "$root/final-preview.exit" $LASTEXITCODE
if ($LASTEXITCODE -ne 0) { throw 'Final preview failed' }
foreach ($probe in 1..5) {
    & ./target/release/examples/scene_preview.exe "$root/final-probe$probe" 0 --capture-hdr --capture-only "--material-probe=$probe" *> "$root/final-probe$probe.log"
    Set-Content "$root/final-probe$probe.exit" $LASTEXITCODE
    if ($LASTEXITCODE -ne 0) { throw "Material probe$probe failed" }
}
& ./target/release/examples/scene_preview.exe "$root/diagnostic4" 0 --scene-msaa=4 --capture-only *> "$root/diagnostic4.log"
Set-Content "$root/diagnostic4.exit" $LASTEXITCODE
if ($LASTEXITCODE -ne 0) { throw '4xMSAA diagnostic failed' }
& ./target/release/examples/scene_preview.exe "$root/diagnostic-full" 0 --scene-full-vertices --capture-only *> "$root/diagnostic-full.log"
Set-Content "$root/diagnostic-full.exit" $LASTEXITCODE
if ($LASTEXITCODE -ne 0) { throw 'Full-vertex diagnostic failed' }
