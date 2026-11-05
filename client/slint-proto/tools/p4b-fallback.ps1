. "$PSScriptRoot/p5-common.ps1"
Require-Credentials
$dir='evidence/bg-port/p4b/fallback'
New-Item -ItemType Directory -Force $dir|Out-Null
$flags=@('--show-others','--all-playable','--scene-memory-probe','--scene-probe-hold-ms=0','--scene-probe-unfocused','--scene-fail')
$p=Start-Process './evidence/bg-port/p4b/controls/p4b-default.exe' -ArgumentList $flags -PassThru -RedirectStandardOutput "$dir/app.log" -RedirectStandardError "$dir/app.err.log"
$handle=[IntPtr]::Zero
try {
 for($i=0;$i -lt 40;$i++){
  Start-Sleep -Seconds 1
  $p.Refresh();if($p.HasExited){throw 'App exited during fallback check'}
  if($handle -eq [IntPtr]::Zero){$handle=[P5Window]::Find($p.Id)}
 }
 Save-Window $handle "$dir/fallback-glass.png"
 Set-Content "$dir/alive.exit" 0
} finally {Stop-Prototype $p $handle;Set-Content "$dir/app.exit" $p.ExitCode}
