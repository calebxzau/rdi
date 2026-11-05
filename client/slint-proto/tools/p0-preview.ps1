$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
& ./target/release/examples/scene_preview.exe evidence/bg-port 0 15 45 *> evidence/bg-port/p0-preview.log
Set-Content evidence/bg-port/p0-preview.exit $LASTEXITCODE
