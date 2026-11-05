$ErrorActionPreference='Continue'
$PSNativeCommandUseErrorActionPreference=$false
Set-Location (Split-Path $PSScriptRoot -Parent)
& ./target/release/examples/scene_preview.exe evidence/bg-port/p1/preview-default 0 15 45 *> evidence/bg-port/p1/preview-default.log
Set-Content evidence/bg-port/p1/preview-default.exit $LASTEXITCODE
& ./target/release/examples/scene_preview.exe evidence/bg-port/p1/preview-best-flags 0 15 45 --scene-msaa=1 --scene-packed --scene-lazy-targets *> evidence/bg-port/p1/preview-best-flags.log
Set-Content evidence/bg-port/p1/preview-best-flags.exit $LASTEXITCODE
& ./target/release/examples/scene_preview.exe evidence/bg-port/p1/preview-diagnostic4 0 --scene-msaa=4 *> evidence/bg-port/p1/preview-diagnostic4.log
Set-Content evidence/bg-port/p1/preview-diagnostic4.exit $LASTEXITCODE
& ./target/release/examples/p1_precision_audit.exe *> evidence/bg-port/p1/precision-audit.json
Set-Content evidence/bg-port/p1/precision-audit.exit $LASTEXITCODE
