$ErrorActionPreference='Continue'
Set-Location (Split-Path $PSScriptRoot -Parent)
while((Get-Content evidence/bg-port/p1/full/current-case.txt -Raw).Trim() -ne 'complete'){Start-Sleep -Seconds 1}
& ./target/release/examples/scene_preview.exe evidence/bg-port/p1/full/diagnostic4 0 --scene-msaa=4 *> evidence/bg-port/p1/full/diagnostic4.log
Set-Content evidence/bg-port/p1/full/diagnostic4.exit $LASTEXITCODE
& ./target/release/examples/scene_preview.exe evidence/bg-port/p1/full/diagnostic-full 0 --scene-full-vertices *> evidence/bg-port/p1/full/diagnostic-full.log
Set-Content evidence/bg-port/p1/full/diagnostic-full.exit $LASTEXITCODE
