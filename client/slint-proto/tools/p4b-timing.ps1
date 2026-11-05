$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$out='evidence/bg-port/p4b/timing'
New-Item -ItemType Directory -Force $out | Out-Null
foreach($run in 1..4){
 foreach($case in @('no-crowd','crowd')){
  [string[]]$flags=if($case -eq 'crowd'){'--crowd=50'}else{'--no-crowd'}
  & ./target/release/examples/scene_preview.exe "$out/$case-$run" '0' @flags *> "$out/$case-$run.log"
  if($LASTEXITCODE -ne 0){throw "timing $case-$run failed"}
 }
}
Set-Content "$out/complete.exit" 0
