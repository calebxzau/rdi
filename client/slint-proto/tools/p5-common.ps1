$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
Add-Type -AssemblyName System.Drawing
Add-Type -TypeDefinition (Get-Content tools/P5Window.cs -Raw)
[P5Window]::SetProcessDpiAwarenessContext([IntPtr]::new(-4)) | Out-Null
function Require-Credentials {
 foreach($key in @('RDI_PROTO_USR','RDI_PROTO_PWD')) {if([string]::IsNullOrEmpty([Environment]::GetEnvironmentVariable($key))){throw 'Owner test-account process environment is missing'}}
}
function Require-Focus([IntPtr]$Window) {
 if([P5Window]::IsIconic($Window)){[P5Window]::ShowWindow($Window,9)|Out-Null}
 for($i=0;$i -lt 25;$i++) {
  $nativeReady=([P5Window]::GetForegroundWindow() -eq $Window -and ![P5Window]::IsIconic($Window) -and [P5Window]::IsWindowVisible($Window))
  $appReady=$true
  if($nativeReady -and $prefix -and (Test-Path "$prefix-app.log")) {
   $text=[regex]::Replace((Get-Content "$prefix-app.log" -Raw),'\x1b\[[0-9;]*m','')
   $beats=[regex]::Matches($text,'Runtime probe data=(\{[^\r\n]+\})')
   if($beats.Count){$state=$beats[$beats.Count-1].Groups[1].Value|ConvertFrom-Json;$appReady=$state.focused -and $state.visible -and !$state.minimized}
  }
  if($nativeReady -and $appReady){return}
  if($nativeReady){
   if($i -eq 6){[P5Window]::Reacquire($Window)}else{[P5Window]::Focus($Window)|Out-Null}
  }

  [P5Window]::Activate($Window)|Out-Null
  Start-Sleep -Milliseconds 200
 }
 throw 'Actual focus/visibility condition failed; this case must be rerun'
}
function Save-Window([IntPtr]$Window,[string]$Path) {
 $r=New-Object P5Window+RECT;[P5Window]::GetWindowRect($Window,[ref]$r)|Out-Null
 $bmp=New-Object System.Drawing.Bitmap ($r.R-$r.L),($r.B-$r.T)
 $g=[System.Drawing.Graphics]::FromImage($bmp);$dc=$g.GetHdc()
 try {$ok=[P5Window]::PrintWindow($Window,$dc,2)} finally {$g.ReleaseHdc($dc)}
 if(!$ok){$g.Dispose();$bmp.Dispose();throw 'PrintWindow failed'}
 $bmp.Save((Join-Path $PWD $Path));$g.Dispose();$bmp.Dispose()
}
function Process-Sample($Process,[IntPtr]$Window,[string]$Phase,$Start) {
 $Process.Refresh();if($Process.HasExited){throw 'Prototype exited before measurement endpoint'}
 $r=New-Object P5Window+RECT;[P5Window]::GetWindowRect($Window,[ref]$r)|Out-Null
 $gpu=@(Get-CimInstance Win32_PerfFormattedData_GPUPerformanceCounters_GPUProcessMemory -ErrorAction SilentlyContinue|Where-Object Name -Like "pid_$($Process.Id)_*")
 $engine=@(Get-CimInstance Win32_PerfFormattedData_GPUPerformanceCounters_GPUEngine -ErrorAction SilentlyContinue|Where-Object Name -Like "pid_$($Process.Id)_*"|ForEach-Object {@{name=$_.Name;utilization=$_.UtilizationPercentage}})
 [pscustomobject]@{Phase=$Phase;ProcessId=$Process.Id;Time=(Get-Date).ToUniversalTime().ToString('o');Seconds=((Get-Date)-$Start).TotalSeconds;WorkingSet=$Process.WorkingSet64;Private=$Process.PrivateMemorySize64;CpuMs=$Process.TotalProcessorTime.TotalMilliseconds;Dedicated=($gpu|Measure-Object DedicatedUsage -Sum).Sum;Shared=($gpu|Measure-Object SharedUsage -Sum).Sum;Engines=$engine;Width=($r.R-$r.L);Height=($r.B-$r.T);Focused=([P5Window]::GetForegroundWindow() -eq $Window);ActualMinimized=[P5Window]::IsIconic($Window);Visible=[P5Window]::IsWindowVisible($Window);ForegroundPid=[P5Window]::ForegroundPid()}
}
function Stop-Prototype($Process,[IntPtr]$Window) {
 $Process.Refresh()
 if(!$Process.HasExited){[P5Window]::PostMessage($Window,0x10,[IntPtr]::Zero,[IntPtr]::Zero)|Out-Null;if(!$Process.WaitForExit(5000)){Stop-Process -Id $Process.Id;$Process.WaitForExit()}}
}
