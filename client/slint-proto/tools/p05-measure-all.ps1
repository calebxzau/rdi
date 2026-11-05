$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
foreach($case in @('static','live','pipelines','chunk','msaa2','msaa1','packed','lazy-drop','best')) {
 Set-Content evidence/bg-port/p0.5/current-case.txt $case
 & pwsh.exe -NoProfile -File tools/p05-run-case.ps1 -Case $case
}
Set-Content evidence/bg-port/p0.5/current-case.txt 'complete'
