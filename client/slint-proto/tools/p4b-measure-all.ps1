$ErrorActionPreference='Continue'
Set-Location (Split-Path $PSScriptRoot -Parent)
$dir='evidence/bg-port/p4b/measure'
New-Item -ItemType Directory -Force $dir|Out-Null
$manifest=[ordered]@{}
function Run-Case([string]$Case,[string]$Label,[bool]$Lifecycle){
 for($attempt=1;$attempt -le 3;$attempt++){
  $tag="$Label-r$attempt"
  if(Test-Path "$dir/$tag-measure.exit"){continue}
  $args=@('-NoProfile','-File','tools/p4b-measure.ps1','-Case',$Case,'-Tag',$tag)
  if($Lifecycle){$args+='-Lifecycle'}
  & pwsh.exe @args *> "$dir/$tag-run.log"
  if($LASTEXITCODE -eq 0 -and (Get-Content "$dir/$tag-measure.exit").Trim() -eq '0'){
   $script:manifest[$Label]=$tag
   Write-Output "$Label ok $tag"
   $script:manifest|ConvertTo-Json|Set-Content "$dir/manifest.json"
   return
  }
  Write-Output "$Label attempt $attempt rejected"
 }
 Write-Output "$Label FAILED"
 Set-Content "$dir/complete.exit" 1
 exit 1
}
Run-Case 'static' 'final-static' $true
Run-Case 'p5-control' 'final-p5' $true
Run-Case 'crowd' 'final-crowd' $true
Run-Case 'no-crowd' 'ab-no-crowd-1' $false
Run-Case 'crowd' 'ab-crowd-1' $false
Run-Case 'no-crowd' 'ab-no-crowd-2' $false
Run-Case 'crowd' 'ab-crowd-2' $false
Set-Content "$dir/complete.exit" 0
