$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
& pwsh.exe -NoProfile -File tools/p0-preview.ps1
& pwsh.exe -NoProfile -File tools/p0-measure-pair.ps1
