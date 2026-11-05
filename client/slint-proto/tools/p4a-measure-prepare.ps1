$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
New-Item -ItemType Directory -Force evidence/bg-port/p4a/launcher-classes | Out-Null
& javac -d evidence/bg-port/p4a/launcher-classes tools/P4aMeasureLauncher.java
if($LASTEXITCODE -ne 0){throw 'Measurement launcher compile failed'}
Set-Content evidence/bg-port/p4a/launcher-manifest.mf "Premain-Class: P4aMeasureLauncher`n" -Encoding ascii
& jar cfm evidence/bg-port/p4a/measure-launcher.jar evidence/bg-port/p4a/launcher-manifest.mf -C evidence/bg-port/p4a/launcher-classes .
if($LASTEXITCODE -ne 0){throw 'Measurement launcher packaging failed'}
