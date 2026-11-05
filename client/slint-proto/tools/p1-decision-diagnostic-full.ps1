$ErrorActionPreference='Continue'
Set-Location (Split-Path $PSScriptRoot -Parent)
while((Get-Content evidence/bg-port/p1/current-case.txt -Raw).Trim() -ne 'complete'){Start-Sleep -Seconds 1}
& ./target/release/examples/scene_preview.exe evidence/bg-port/p1/preview-diagnostic-full 0 --scene-msaa=4 --scene-full-vertices *> evidence/bg-port/p1/preview-diagnostic-full.log
Set-Content evidence/bg-port/p1/preview-diagnostic-full.exit $LASTEXITCODE
