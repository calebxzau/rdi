$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
& pwsh.exe -NoProfile -File tools/p0-measure.ps1 -Mode static -Tag p0-authenticated *> evidence/bg-port/p0-authenticated-static-measure.log
Set-Content evidence/bg-port/p0-authenticated-static-measure.exit $LASTEXITCODE
& pwsh.exe -NoProfile -File tools/p0-measure.ps1 -Mode scene -Tag p0-authenticated *> evidence/bg-port/p0-authenticated-scene-measure.log
Set-Content evidence/bg-port/p0-authenticated-scene-measure.exit $LASTEXITCODE
