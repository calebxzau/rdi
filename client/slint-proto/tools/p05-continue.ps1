$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
while(!(Test-Path evidence/bg-port/p0.5/old-static-app.exit)){Start-Sleep -Seconds 1}
while((Get-Item evidence/bg-port/p0.5/release.log).LastWriteTime -lt (Get-Date).AddSeconds(-1) -and !(Select-String 'Finished `release`' evidence/bg-port/p0.5/release.log -Quiet)){Start-Sleep -Seconds 1}
if((Get-Content evidence/bg-port/p0.5/release.exit).Trim() -ne '0'){throw 'Release build failed'}
& pwsh.exe -NoProfile -File tools/p05-preview-all.ps1
& pwsh.exe -NoProfile -File tools/p05-measure-all.ps1
