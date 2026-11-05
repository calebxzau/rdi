$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
& ./target/release/examples/scene_preview.exe evidence/bg-port/p4a/delivery-check 0 --capture-only --dump-player *> evidence/bg-port/p4a/delivery-check.log
Set-Content evidence/bg-port/p4a/delivery-check.exit $LASTEXITCODE
if($LASTEXITCODE -ne 0){throw 'Final P4a preview failed'}
