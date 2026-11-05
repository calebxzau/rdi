$ErrorActionPreference='Stop'
Set-Location (Join-Path $PSScriptRoot '../../ui')
& ./gradlew.bat :bg-renderer:p1VisualClasspath -I ../slint-proto/tools/p1-visual-classpath.init.gradle --daemon --no-configuration-cache *> ../slint-proto/evidence/bg-port/p1/visual/kotlin-classpath.log
Set-Content ../slint-proto/evidence/bg-port/p1/visual/kotlin-classpath.exit $LASTEXITCODE
if($LASTEXITCODE -ne 0){throw 'Reference classpath task failed'}
Set-Location (Split-Path $PSScriptRoot -Parent)
$cp=Get-Content evidence/bg-port/p1/visual/kotlin-classpath.txt -Raw
& java --enable-native-access=ALL-UNNAMED --class-path $cp tools/P1VisualProbe.java evidence/bg-port/p1/visual/reference *> evidence/bg-port/p1/visual/kotlin-probe.log
Set-Content evidence/bg-port/p1/visual/kotlin-probe.exit $LASTEXITCODE
if($LASTEXITCODE -ne 0){throw 'Reference visual probe failed'}
