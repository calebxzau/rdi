$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
foreach($case in @('p2','p3')) {
    & pwsh.exe -NoProfile -File tools/p3-cpu-app.ps1 -Case $case
    if($LASTEXITCODE -ne 0){throw "Authenticated memory case $case failed"}
    if((Get-Content "evidence/bg-port/p3/cpu-app/$case-measure.exit").Trim() -ne '0'){throw "Controlled case $case failed"}
}
Set-Content evidence/bg-port/p3/cpu-app/complete.exit 0
