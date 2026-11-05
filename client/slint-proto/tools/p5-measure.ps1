param([ValidateSet('static','scene','p3','p2','steve','skip-bloom','skip-fog','skip-copy','skip-mip','skip-final','unrendered')][string]$Case='scene',[string]$Tag='',[switch]$Lifecycle,[switch]$Probe)
. "$PSScriptRoot/p5-common.ps1"
Require-Credentials
New-Item -ItemType Directory -Force evidence/bg-port/p5/measure|Out-Null
$tag=if($Tag){$Tag}else{$Case};$prefix="evidence/bg-port/p5/measure/$tag"
if(Test-Path "$prefix-memory.jsonl"){throw 'Existing measurement output; use a new tag to preserve evidence'}
$exe=if($Probe){'./evidence/bg-port/p5/controls/p5-probe.exe'}else{'./evidence/bg-port/p5/controls/p5-default.exe'}
$flags=@('--show-others','--all-playable','--scene-memory-probe','--scene-probe-hold-ms=0')
switch($Case) {
 'static' {$flags+='--no-scene'}
 'p3' {$flags+='--scene-p3-only'}
 'p2' {$flags+='--scene-p2-only'}
 'steve' {$flags+='--scene-steve-only'}
 'unrendered' {$flags+=@('--scene-p3-only','--scene-post-create-only')}
 default {if($Case.StartsWith('skip-')){$flags+=@('--scene-p3-only',"--scene-skip-post=$($Case.Substring(5))")}}
}
$p=Start-Process $exe -ArgumentList $flags -PassThru -RedirectStandardOutput "$prefix-app.log" -RedirectStandardError "$prefix-app.err.log"
$start=Get-Date;$handle=[IntPtr]::Zero;$ready=$null;$minimized=$false;$resumed=$false;$last='startup'
try {
 while($true) {
  $p.Refresh();if($p.HasExited){throw 'App exited before endpoint'}
  if($handle -eq [IntPtr]::Zero){$handle=[P5Window]::Find($p.Id)}
  if($handle -ne [IntPtr]::Zero -and !$minimized){Require-Focus $handle}
  $log=if(Test-Path "$prefix-app.log"){Get-Content "$prefix-app.log" -Raw}else{''}
  if($log -match 'Menu login/load failed|Scene unavailable|Scene loader failed|terminal static fallback'){throw 'App readiness/fallback failure; invalid comparison case'}
  if(!$ready){
   $done=if($Case -eq 'static'){$log -match 'First frame'}else{$log -match 'First live scene frame'}
   if($Case -eq 'scene'){$done=$done -and $log -match 'Account player appearance ready'}
   if($done -and $log -match 'Menu data loaded'){$ready=Get-Date;$last='loaded'}
   elseif(((Get-Date)-$start).TotalSeconds -gt 90){throw 'Readiness timeout'}
  }
  if($ready){
   $elapsed=((Get-Date)-$ready).TotalSeconds
   if($elapsed -ge 60 -and !$minimized -and !$resumed){
    Require-Focus $handle
    $r=Process-Sample $p $handle 'foreground60s' $start
    if(!$r.Focused -or $r.ActualMinimized){throw 'Visible endpoint state mismatch; rerun case'}
    $r|ConvertTo-Json -Depth 6 -Compress|Add-Content "$prefix-memory.jsonl"
    Save-Window $handle "$prefix-glass.png"
    if(!$Lifecycle){break}
    [P5Window]::ShowWindow($handle,6)|Out-Null;$minimized=$true;$last='minimized'
   } elseif($elapsed -ge 90 -and $minimized){
    $r=Process-Sample $p $handle 'minimized30s' $start
    if(!$r.ActualMinimized){throw 'Minimized endpoint mismatch; rerun case'}
    $r|ConvertTo-Json -Depth 6 -Compress|Add-Content "$prefix-memory.jsonl"
    [P5Window]::ShowWindow($handle,9)|Out-Null;Require-Focus $handle;$minimized=$false;$resumed=$true;$last='restored'
   } elseif($elapsed -ge 100 -and $resumed){
    Require-Focus $handle;$r=Process-Sample $p $handle 'restored10s' $start
    if(!$r.Focused -or $r.ActualMinimized){throw 'Restored endpoint state mismatch; rerun case'}
    $r|ConvertTo-Json -Depth 6 -Compress|Add-Content "$prefix-memory.jsonl";break
   }
  }
  if($handle -ne [IntPtr]::Zero){Process-Sample $p $handle $last $start|ConvertTo-Json -Depth 6 -Compress|Add-Content "$prefix-memory.jsonl"}
  Start-Sleep -Milliseconds 700
 }
 Set-Content "$prefix-measure.exit" 0
} catch {$_|Out-String|Set-Content "$prefix-measure-error.log";Set-Content "$prefix-measure.exit" 1;throw}
finally {Stop-Prototype $p $handle;Set-Content "$prefix-app.exit" $p.ExitCode}
