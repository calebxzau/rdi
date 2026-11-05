$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
while(!(Test-Path evidence/bg-port/p1/full/mesh-report.exit)){Start-Sleep -Seconds 1}
while((Get-Item evidence/bg-port/p1/full/preview.exit).LastWriteTime -lt (Get-Item evidence/bg-port/p1/full/mesh-report.exit).LastWriteTime){Start-Sleep -Seconds 1}
if((Get-Content evidence/bg-port/p1/full/preview.exit).Trim() -ne '0'){throw 'Preview failed; measure only a valid build'}
foreach($case in @('static','baseline','live')){
 Set-Content evidence/bg-port/p1/full/current-case.txt $case
 & pwsh.exe -NoProfile -File tools/p1-full-measure.ps1 -Case $case
}
Set-Content evidence/bg-port/p1/full/current-case.txt 'complete'
