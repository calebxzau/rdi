$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
& pwsh.exe -NoProfile -File tools/p4a-measure.ps1 -Case static -OutputTag static-aligned
if($LASTEXITCODE -ne 0 -or (Get-Content evidence/bg-port/p4a/measure/static-aligned-measure.exit).Trim() -ne '0'){throw 'Aligned static failed'}
