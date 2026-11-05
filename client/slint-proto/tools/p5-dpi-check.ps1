$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
& pwsh.exe -NoProfile -File tools/p5-runtime.ps1 -Case policy -Tag policy-native-dpi-r1 *> evidence/bg-port/p5/runtime-policy-native-dpi-r1-run.log
if($LASTEXITCODE -ne 0){throw 'Native DPI policy check failed'}
$manifest=Get-Content evidence/bg-port/p5/runtime/manifest.json -Raw|ConvertFrom-Json -AsHashtable
$manifest['policy']='policy-native-dpi-r1'
$manifest|ConvertTo-Json|Set-Content evidence/bg-port/p5/runtime/manifest.json
Set-Content evidence/bg-port/p5/native-dpi-complete.exit 0
