$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
New-Item -ItemType Directory -Force evidence/bg-port/p5/launcher-classes | Out-Null
& javac -d evidence/bg-port/p5/launcher-classes tools/P5LastPairLauncher.java
if($LASTEXITCODE -ne 0){throw 'Measurement launcher compile failed'}
Set-Content evidence/bg-port/p5/last-pair-launcher-manifest.mf "Premain-Class: P5LastPairLauncher`n" -Encoding ascii
& jar cfm evidence/bg-port/p5/last-pair-launcher.jar evidence/bg-port/p5/last-pair-launcher-manifest.mf -C evidence/bg-port/p5/launcher-classes .
if($LASTEXITCODE -ne 0){throw 'Measurement launcher packaging failed'}
