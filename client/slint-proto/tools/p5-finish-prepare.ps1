$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
New-Item -ItemType Directory -Force evidence/bg-port/p5/launcher-classes | Out-Null
& javac -d evidence/bg-port/p5/launcher-classes tools/P5FinishLauncher.java
if($LASTEXITCODE -ne 0){throw 'Measurement launcher compile failed'}
Set-Content evidence/bg-port/p5/finish-launcher-manifest.mf "Premain-Class: P5FinishLauncher`n" -Encoding ascii
& jar cfm evidence/bg-port/p5/finish-launcher.jar evidence/bg-port/p5/finish-launcher-manifest.mf -C evidence/bg-port/p5/launcher-classes .
if($LASTEXITCODE -ne 0){throw 'Measurement launcher packaging failed'}
