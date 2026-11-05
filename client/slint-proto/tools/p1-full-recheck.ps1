$ErrorActionPreference='Continue'
$PSNativeCommandUseErrorActionPreference=$false
Set-Location (Split-Path $PSScriptRoot -Parent)
while(!(Select-String 'Finished `release`|error: could not compile' evidence/bg-port/p1/full/release.log -Quiet)){Start-Sleep -Seconds 1}
& pwsh.exe -NoProfile -File tools/p1-full-validate.ps1
if((Get-Content evidence/bg-port/p1/full/release.exit).Trim() -eq '0'){ & pwsh.exe -NoProfile -File tools/p1-full-preview.ps1 }
