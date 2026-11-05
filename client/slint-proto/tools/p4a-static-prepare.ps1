$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
& javac -d evidence/bg-port/p4a/launcher-classes tools/P4aStaticLauncher.java
if($LASTEXITCODE -ne 0){throw 'Static launcher compile failed'}
Set-Content evidence/bg-port/p4a/static-launcher-manifest.mf "Premain-Class: P4aStaticLauncher`n" -Encoding ascii
& jar cfm evidence/bg-port/p4a/static-launcher.jar evidence/bg-port/p4a/static-launcher-manifest.mf -C evidence/bg-port/p4a/launcher-classes .
if($LASTEXITCODE -ne 0){throw 'Static launcher package failed'}
