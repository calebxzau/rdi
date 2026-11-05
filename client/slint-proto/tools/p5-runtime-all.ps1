$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
New-Item -ItemType Directory -Force evidence/bg-port/p5/runtime|Out-Null
$manifest=if(Test-Path evidence/bg-port/p5/runtime/manifest.json){Get-Content evidence/bg-port/p5/runtime/manifest.json -Raw|ConvertFrom-Json -AsHashtable}else{@{}}
foreach($case in @('policy','fail','slow','loader-fail','no-glass')){
 if($manifest.ContainsKey($case)){continue}
 $passed=$false
 for($attempt=1;$attempt -le 6;$attempt++){
  $tag="$case-r$attempt"
  if(Test-Path "evidence/bg-port/p5/runtime/$tag-runtime.exit"){continue}
  & pwsh.exe -NoProfile -File tools/p5-runtime.ps1 -Case $case -Tag $tag *> "evidence/bg-port/p5/runtime-$tag-run.log"
  if($LASTEXITCODE -eq 0){
   $manifest[$case]=$tag;$manifest|ConvertTo-Json|Set-Content evidence/bg-port/p5/runtime/manifest.json
   $passed=$true;break
  }
 }
 if(!$passed){throw "Runtime case $case failed; preserve outputs and investigate"}
}
Set-Content evidence/bg-port/p5/runtime-complete.exit 0
