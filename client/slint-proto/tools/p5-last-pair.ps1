$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
& pwsh.exe -NoProfile -File tools/p5-measure-all.ps1
if($LASTEXITCODE -ne 0){throw 'Final clean static measurement failed'}
Set-Content evidence/bg-port/p5/live-complete.exit 0
