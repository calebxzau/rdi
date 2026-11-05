$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
& pwsh.exe -NoProfile -File tools/p4a-measure.ps1 -Case p4a -OutputTag p4a-aligned
if($LASTEXITCODE -ne 0 -or (Get-Content evidence/bg-port/p4a/measure/p4a-aligned-measure.exit).Trim() -ne '0'){throw 'Aligned P4a failed'}
