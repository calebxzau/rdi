$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
& pwsh.exe -NoProfile -File tools/p5-runtime-all.ps1
if($LASTEXITCODE -ne 0){throw 'P5 runtime validation did not complete'}
& pwsh.exe -NoProfile -File tools/p5-measure-all.ps1
if($LASTEXITCODE -ne 0){throw 'P5 focused measurement did not complete'}
Set-Content evidence/bg-port/p5/live-complete.exit 0
