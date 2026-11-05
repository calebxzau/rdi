$ErrorActionPreference='Stop'
Set-Location (Join-Path $PSScriptRoot '../../ui')
$env:RDI_BG_PREVIEW_SCENE='valley'
& ./gradlew.bat :bg-renderer:renderPreview '-PpreviewTimes=0,15,45' :bg-renderer:p1VisualClasspath -I ../slint-proto/tools/p1-visual-classpath.init.gradle --daemon --no-configuration-cache *> ../slint-proto/evidence/bg-port/p2/kotlin-preview.log
Set-Content ../slint-proto/evidence/bg-port/p2/kotlin-preview.exit $LASTEXITCODE
if($LASTEXITCODE -ne 0){throw 'Kotlin preview failed'}
Set-Location (Split-Path $PSScriptRoot -Parent)
New-Item -ItemType Directory -Force evidence/bg-port/p2/reference | Out-Null
foreach($time in @('0.0','15.0','45.0')) {Copy-Item "../bg-renderer/build/bg-preview/valley/frame-$time.png" "evidence/bg-port/p2/reference/kotlin-original-$time.png"}
$cp=Get-Content evidence/bg-port/p1/visual/kotlin-classpath.txt -Raw
& javac -classpath $cp -d evidence/bg-port/p2/probe-classes tools/P1VisualProbe.java tools/P2ReferenceProbe.java *> evidence/bg-port/p2/probe-compile.log
if($LASTEXITCODE -ne 0){throw 'Reference probe compilation failed'}
& java --enable-native-access=ALL-UNNAMED --class-path "$cp;evidence/bg-port/p2/probe-classes" P2ReferenceProbe evidence/bg-port/p2/reference *> evidence/bg-port/p2/reference-probe.log
Set-Content evidence/bg-port/p2/reference-probe.exit $LASTEXITCODE
if($LASTEXITCODE -ne 0){throw 'Reference probe failed'}
