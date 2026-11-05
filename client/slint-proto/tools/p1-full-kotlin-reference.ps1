$ErrorActionPreference='Stop'
Set-Location (Join-Path $PSScriptRoot '../../ui')
$env:RDI_BG_PREVIEW_SCENE='valley'
& ./gradlew.bat :bg-renderer:renderPreview '-PpreviewTimes=0,15,45' --daemon *> ../slint-proto/evidence/bg-port/p1/full/kotlin-preview.log
Set-Content ../slint-proto/evidence/bg-port/p1/full/kotlin-preview.exit $LASTEXITCODE
if($LASTEXITCODE -eq 0){
 New-Item -ItemType Directory -Force ../slint-proto/evidence/bg-port/p1/full/reference | Out-Null
 foreach($time in @('0.0','15.0','45.0')) {Copy-Item "../bg-renderer/build/bg-preview/valley/frame-$time.png" "../slint-proto/evidence/bg-port/p1/full/reference/kotlin-$time.png"}
}
