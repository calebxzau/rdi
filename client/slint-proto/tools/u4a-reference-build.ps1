$ErrorActionPreference='Stop'
$client=Split-Path (Split-Path $PSScriptRoot)
Set-Location "$client/ui"
& .\gradlew.bat u4aReferenceClasspath --init-script ../slint-proto/tools/u4a-reference.init.gradle --no-configuration-cache --no-daemon -x :net:compileTestKotlin *> ../slint-proto/evidence/ui-port/u4a/reference-build.log
$LASTEXITCODE|Set-Content ../slint-proto/evidence/ui-port/u4a/reference-build.exit
