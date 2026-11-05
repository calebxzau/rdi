$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
& "$PSScriptRoot/u1-validate.ps1"
& "$PSScriptRoot/u1-runtime.ps1" -Tag 'focused-images' -Rounds 3

& "$PSScriptRoot/u1-auth.ps1" -Tag "auth-final"

& "$PSScriptRoot/u1-captures.ps1"
