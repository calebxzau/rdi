$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$base=$PWD
$out='evidence/bg-port/p4b'
New-Item -ItemType Directory -Force "$out/reference" | Out-Null
Set-Location (Join-Path $PSScriptRoot '../../ui')
$env:RDI_BG_PREVIEW_SCENE='valley'
& ./gradlew.bat :bg-renderer:renderPreview '-PpreviewTimes=0,15,45' :bg-renderer:p1VisualClasspath -I ../slint-proto/tools/p1-visual-classpath.init.gradle --daemon --no-configuration-cache *> "../slint-proto/$out/kotlin-preview.log"
Set-Content "../slint-proto/$out/kotlin-preview.exit" $LASTEXITCODE
if($LASTEXITCODE -ne 0){throw 'Kotlin preview failed'}
Set-Location $base
foreach($time in @('0.0','15.0','45.0')) {Copy-Item "../bg-renderer/build/bg-preview/valley/frame-$time.png" "$out/reference/kotlin-original-$time.png"}
$cp=Get-Content evidence/bg-port/p1/visual/kotlin-classpath.txt -Raw
& javac -classpath $cp -d "$out/probe-classes" tools/P1VisualProbe.java tools/P2ReferenceProbe.java tools/P3ReferenceProbe.java tools/P4aReferenceProbe.java tools/P4bReferenceProbe.java *> "$out/probe-compile.log"
if($LASTEXITCODE -ne 0){throw 'Reference probe compilation failed'}
& java --enable-native-access=ALL-UNNAMED --class-path "$cp;$out/probe-classes" P4bReferenceProbe "$out/reference" "$out/fixtures/mixed" *> "$out/reference-probe.log"
Set-Content "$out/reference-probe.exit" $LASTEXITCODE
if($LASTEXITCODE -ne 0){throw 'Reference probe failed'}
