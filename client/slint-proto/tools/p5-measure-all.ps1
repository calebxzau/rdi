$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
if((Get-Content evidence/bg-port/p5/validation-complete.exit).Trim() -ne '0'){throw 'P5 build is not ready'}
New-Item -ItemType Directory -Force evidence/bg-port/p5/measure|Out-Null
$manifest=if(Test-Path evidence/bg-port/p5/measure/manifest.json){Get-Content evidence/bg-port/p5/measure/manifest.json -Raw|ConvertFrom-Json -AsHashtable}else{@{}}
function Run-Case([string]$Case,[string]$Label,[bool]$Lifecycle,[bool]$Probe) {
 if($script:manifest.ContainsKey($Label)){return}
 for($attempt=1;$attempt -le 6;$attempt++) {
  $tag="$Label-r$attempt"
  if(Test-Path "evidence/bg-port/p5/measure/$tag-measure.exit"){continue}
  $args=@('-NoProfile','-File','tools/p5-measure.ps1','-Case',$Case,'-Tag',$tag)
  if($Lifecycle){$args+='-Lifecycle'};if($Probe){$args+='-Probe'}
  & pwsh.exe @args *> "evidence/bg-port/p5/measure/$tag-run.log"
  if($LASTEXITCODE -eq 0 -and (Get-Content "evidence/bg-port/p5/measure/$tag-measure.exit").Trim() -eq '0'){
   $text=Get-Content "evidence/bg-port/p5/measure/$tag-app.log" -Raw
   if($text -match '\b(?:WARN|ERROR)\b'){continue}
   $script:manifest[$Label]=$tag
   $script:manifest|ConvertTo-Json|Set-Content evidence/bg-port/p5/measure/manifest.json
   return
  }
 }
 throw "Actual endpoint/readiness condition failed; attempts exhausted: $Label. Preserve evidence and rerun with fresh tags."
}
# Native A/B first; fixed executable/counters, post controls have no player.
foreach($case in @('p3','skip-bloom','skip-fog','skip-copy','skip-mip','skip-final','unrendered','p2','steve','scene')) {
 Run-Case $case "ab-$case" $false $true
}
# Repeat the no-player P3 baseline to expose sequential run drift.
Run-Case 'p3' 'ab-p3-repeat' $false $true
# Final report uses normal/default executable, production focus policy, matched lifecycle.
Run-Case 'static' 'final-static' $true $false
Run-Case 'scene' 'final-scene' $true $false
Set-Content evidence/bg-port/p5/measure/complete.exit 0
