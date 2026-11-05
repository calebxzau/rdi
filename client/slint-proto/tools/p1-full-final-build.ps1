$ErrorActionPreference='Continue'
$PSNativeCommandUseErrorActionPreference=$false
Set-Location (Split-Path $PSScriptRoot -Parent)
& pwsh.exe -NoProfile -File tools/p1-full-validate.ps1
if((Get-Content evidence/bg-port/p1/full/release.exit).Trim() -eq '0'){
 & ./target/release/examples/p1_mesh_report.exe *> evidence/bg-port/p1/full/mesh-report.json
 Set-Content evidence/bg-port/p1/full/mesh-report.exit $LASTEXITCODE
 & pwsh.exe -NoProfile -File tools/p1-full-preview.ps1
}
