$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '../../ui')
$env:RDI_BG_PREVIEW_SCENE = 'valley'
& ./gradlew.bat :bg-renderer:renderPreview '-PpreviewTimes=0,15,45' --daemon *> ../slint-proto/evidence/p0-kotlin-preview.log
Set-Content ../slint-proto/evidence/p0-kotlin-preview.exit $LASTEXITCODE
