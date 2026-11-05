$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
& pwsh.exe -NoProfile -File tools/p4a-preview.ps1
if($LASTEXITCODE -ne 0){throw 'P4a previews failed'}
& pwsh.exe -NoProfile -File tools/p4a-reference.ps1
if($LASTEXITCODE -ne 0){throw 'P4a reference failed'}
Set-Content evidence/bg-port/p4a/visual-complete.exit 0
