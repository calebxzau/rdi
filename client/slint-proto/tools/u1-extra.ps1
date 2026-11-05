$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$deadline=(Get-Date).AddMinutes(5)
while (!(Test-Path evidence/ui-port/u1/auth-final/exit.code)) {
    if ((Get-Date) -gt $deadline) { throw 'Waiting for the authenticated run timed out' }
    Start-Sleep -Seconds 1
}
& "$PSScriptRoot/u1-captures.ps1"
& "$PSScriptRoot/u1-window-input.ps1"
