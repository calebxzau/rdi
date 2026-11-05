param([ValidateSet('policy','fail','slow','loader-fail','no-glass')][string]$Case='policy',[string]$Tag='')
. "$PSScriptRoot/p5-common.ps1"
Require-Credentials
New-Item -ItemType Directory -Force evidence/bg-port/p5/runtime|Out-Null
$tag=if($Tag){$Tag}else{$Case};$prefix="evidence/bg-port/p5/runtime/$tag"
if(Test-Path "$prefix-memory.jsonl"){throw 'Existing runtime evidence; select a new tag'}
$flags=@('--show-others','--all-playable','--scene-memory-probe','--scene-probe-hold-ms=0')
switch($Case){'fail'{$flags+='--scene-fail'}'slow'{$flags+='--scene-low-performance-ms=0.001'}'loader-fail'{$flags+='--scene-startup-fail'}'no-glass'{$flags+='--no-glass'}}
$control=Join-Path $PWD "$prefix-control.json";$env:RDI_PROTO_CONTROL=$control
$p=Start-Process ./evidence/bg-port/p5/controls/p5-default.exe -ArgumentList $flags -PassThru -RedirectStandardOutput "$prefix-app.log" -RedirectStandardError "$prefix-app.err.log"
$start=Get-Date;$handle=[IntPtr]::Zero;$target=[IntPtr]::Zero;$id=0
function Log {if(Test-Path "$prefix-app.log"){Get-Content "$prefix-app.log" -Raw}else{''}}
function Command([string]$Action,[double]$Value=0){$script:id++;@{id=$script:id;action=$Action;value=$Value}|ConvertTo-Json -Compress|Set-Content $control;Start-Sleep -Seconds 2}
function Sample([string]$Phase,[int]$Seconds,[bool]$Focus=$true){
 for($i=0;$i -lt $Seconds;$i++) {
  if($Focus){Require-Focus $handle}
  Process-Sample $p $handle $Phase $start|ConvertTo-Json -Depth 6 -Compress|Add-Content "$prefix-memory.jsonl"
  Start-Sleep -Milliseconds 800
 }
}
try {
 $deadline=(Get-Date).AddSeconds(90)
 do {
  $p.Refresh();if($p.HasExited){throw 'Runtime app exited during startup'}
  if($handle -eq [IntPtr]::Zero){$handle=[P5Window]::Find($p.Id)}
  if($handle -ne [IntPtr]::Zero){Require-Focus $handle;Process-Sample $p $handle 'startup' $start|ConvertTo-Json -Depth 6 -Compress|Add-Content "$prefix-memory.jsonl"}
  $log=Log
  $done=if($Case -eq 'no-glass'){$true}elseif($Case -eq 'loader-fail'){$log -match 'Scene unavailable'}else{$log -match 'First live scene frame'}
  $ready=($log -match 'Menu data loaded') -and $done
  if((Get-Date) -gt $deadline){throw 'Runtime readiness timeout'}
  Start-Sleep -Milliseconds 500
 }while(!$ready)
 if($Case -eq 'policy') {
  Sample 'focused-live' 8
  Save-Window $handle "$prefix-live.png"
  $target=[P5Window]::FocusTarget();[P5Window]::Activate($target)|Out-Null
  if([P5Window]::GetForegroundWindow() -eq $handle){throw 'Failed to make prototype actually unfocused'}
  Sample 'unfocused' 8 $false
  Require-Focus $handle;Sample 'refocused' 5
  Command 'hide';Sample 'hidden' 6 $false
  Command 'show';Require-Focus $handle;Sample 'shown' 5
  [P5Window]::ShowWindow($handle,6)|Out-Null;Sample 'minimized' 8 $false
  if(![P5Window]::IsIconic($handle)){throw 'Runtime minimize mismatch'}
  [P5Window]::ShowWindow($handle,9)|Out-Null;Require-Focus $handle;Sample 'restored' 6
  [P5Window]::SetWindowPos($handle,[IntPtr]::Zero,0,0,1920,1080,6)|Out-Null;Sample 'resized-small' 5
  Save-Window $handle "$prefix-resized.png"
  [P5Window]::Dpi($handle,144);Start-Sleep -Seconds 2;Sample 'scale-1.5' 5;Save-Window $handle "$prefix-scale1.5.png"
  [P5Window]::Dpi($handle,192);Start-Sleep -Seconds 2;[P5Window]::SetWindowPos($handle,[IntPtr]::Zero,0,0,2560,1440,6)|Out-Null
  Sample 'restored-size-scale' 5
  Save-Window $handle "$prefix-restored.png"
 } elseif($Case -eq 'no-glass') {
  Sample 'no-glass' 8;Save-Window $handle "$prefix-glass.png"
 } else {
  $deadline=(Get-Date).AddSeconds(30)
  while((Log) -notmatch 'Terminal scene state') {
   Require-Focus $handle;Process-Sample $p $handle 'before-fallback' $start|ConvertTo-Json -Depth 6 -Compress|Add-Content "$prefix-memory.jsonl"
   if((Get-Date) -gt $deadline){throw 'Terminal fallback did not occur'}
   Start-Sleep -Milliseconds 500
  }
  Sample 'fallback-stable' 12
  Save-Window $handle "$prefix-fallback.png"
  [P5Window]::SetWindowPos($handle,[IntPtr]::Zero,0,0,1920,1080,6)|Out-Null;Sample 'fallback-resize' 4
  [P5Window]::ShowWindow($handle,6)|Out-Null;Sample 'fallback-minimized' 5 $false
  [P5Window]::ShowWindow($handle,9)|Out-Null;Require-Focus $handle;Sample 'fallback-restored' 8
  Save-Window $handle "$prefix-fallback-restored.png"
 }
 Set-Content "$prefix-runtime.exit" 0
}catch{$_|Out-String|Set-Content "$prefix-error.log";Set-Content "$prefix-runtime.exit" 1;throw}
finally{if($target -ne [IntPtr]::Zero){[P5Window]::DestroyWindow($target)|Out-Null};Stop-Prototype $p $handle;Set-Content "$prefix-app.exit" $p.ExitCode;$env:RDI_PROTO_CONTROL=$null}
