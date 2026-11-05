$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
foreach($case in @('static','p1','p2')) {
    & pwsh.exe -NoProfile -File tools/p2-measure.ps1 -Case $case
    if($LASTEXITCODE -ne 0){throw "Authenticated memory case $case failed"}
    if((Get-Content "evidence/bg-port/p2/measure/$case-measure.exit").Trim() -ne '0'){throw "Controlled case $case failed"}
}
Set-Content evidence/bg-port/p2/measure/complete.exit 0
