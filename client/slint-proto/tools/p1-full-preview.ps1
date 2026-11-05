$ErrorActionPreference='Continue'
Set-Location (Split-Path $PSScriptRoot -Parent)
& ./target/release/examples/scene_preview.exe evidence/bg-port/p1/full/preview 0 15 45 *> evidence/bg-port/p1/full/preview.log
Set-Content evidence/bg-port/p1/full/preview.exit $LASTEXITCODE
