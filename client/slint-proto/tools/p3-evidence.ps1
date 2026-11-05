$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
& pwsh.exe -NoProfile -File tools/p3-preview.ps1
if($LASTEXITCODE -ne 0){throw 'P3 previews failed'}
& pwsh.exe -NoProfile -File tools/p3-reference.ps1
if($LASTEXITCODE -ne 0){throw 'P3 reference failed'}
Set-Content evidence/bg-port/p3/visual-complete.exit 0
