$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
& pwsh.exe -NoProfile -File tools/p5-dpi-check.ps1
if($LASTEXITCODE -ne 0){throw 'Native DPI verification failed'}
& pwsh.exe -NoProfile -File tools/p5-measure-all.ps1
if($LASTEXITCODE -ne 0){throw 'Strict final focus pair failed'}
Set-Content evidence/bg-port/p5/live-complete.exit 0
