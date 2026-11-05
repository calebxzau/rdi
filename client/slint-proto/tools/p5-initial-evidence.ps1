$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
& pwsh.exe -NoProfile -File tools/p5-preview.ps1
if($LASTEXITCODE -ne 0){throw 'P5 preview failed'}
& pwsh.exe -NoProfile -File tools/p5-reference.ps1
if($LASTEXITCODE -ne 0){throw 'P5 reference failed'}
Set-Content evidence/bg-port/p5/initial-evidence.exit 0
