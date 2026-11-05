$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
New-Item -ItemType Directory -Force evidence/bg-port/p3/launcher-classes | Out-Null
& javac -d evidence/bg-port/p3/launcher-classes tools/P3MeasureLauncher.java
if($LASTEXITCODE -ne 0){throw 'Measurement launcher compile failed'}
Set-Content evidence/bg-port/p3/launcher-manifest.mf "Premain-Class: P3MeasureLauncher`n" -Encoding ascii
& jar cfm evidence/bg-port/p3/measure-launcher.jar evidence/bg-port/p3/launcher-manifest.mf -C evidence/bg-port/p3/launcher-classes .
if($LASTEXITCODE -ne 0){throw 'Measurement launcher packaging failed'}
