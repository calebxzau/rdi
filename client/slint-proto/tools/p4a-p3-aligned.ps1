$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
& pwsh.exe -NoProfile -File tools/p4a-measure.ps1 -Case p3 -OutputTag p3-aligned
if($LASTEXITCODE -ne 0 -or (Get-Content evidence/bg-port/p4a/measure/p3-aligned-measure.exit).Trim() -ne '0'){throw 'Aligned P3 failed'}
