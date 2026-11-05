$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
& ./target/release/examples/scene_preview.exe evidence/bg-port/p5/delivery-check 0 15 45 --capture-only *> evidence/bg-port/p5/delivery-check.log
Set-Content evidence/bg-port/p5/delivery-check.exit $LASTEXITCODE
if($LASTEXITCODE -ne 0){throw 'Final P5 delivery capture failed'}
