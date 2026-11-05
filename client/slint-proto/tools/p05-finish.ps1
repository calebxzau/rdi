$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
while((Get-Content evidence/bg-port/p0.5/current-case.txt -Raw).Trim() -ne 'complete'){Start-Sleep -Seconds 1}
& pwsh.exe -NoProfile -File tools/p05-run-case.ps1 -Case packed-checked
& pwsh.exe -NoProfile -File tools/p05-reference.ps1
Set-Content evidence/bg-port/p0.5/finished.exit $LASTEXITCODE
