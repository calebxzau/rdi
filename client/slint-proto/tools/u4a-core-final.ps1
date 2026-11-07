$ErrorActionPreference='Stop'
Set-Location "$PSScriptRoot/../../ui"
& .\gradlew.bat :client-core:test --rerun-tasks --no-daemon -x :net:compileTestKotlin *> ../slint-proto/evidence/ui-port/u4a/final-client-core.log
$LASTEXITCODE|Set-Content ../slint-proto/evidence/ui-port/u4a/final-client-core.exit
