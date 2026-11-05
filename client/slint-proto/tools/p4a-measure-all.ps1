$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
Copy-Item target/release/rdi-slint-proto.exe evidence/bg-port/p4a/controls/p4a-measure.exe
foreach($case in @('static','p3','p4a')) {
    & pwsh.exe -NoProfile -File tools/p4a-measure.ps1 -Case $case
    if($LASTEXITCODE -ne 0){throw "Authenticated memory case $case failed"}
    if((Get-Content "evidence/bg-port/p4a/measure/$case-measure.exit").Trim() -ne '0'){throw "Controlled case $case failed"}
}
Set-Content evidence/bg-port/p4a/measure/complete.exit 0
