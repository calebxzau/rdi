param([string]$Case='live')
$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
Add-Type -AssemblyName System.Drawing
Add-Type @'
using System;
using System.Runtime.InteropServices;
public class P05Window {
 [StructLayout(LayoutKind.Sequential)] public struct RECT {public int L,T,R,B;}
 public delegate bool WindowEnum(IntPtr hwnd,IntPtr p);
 [DllImport("user32.dll")] static extern bool EnumWindows(WindowEnum callback,IntPtr p);
 [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hwnd,out RECT rect);
 [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr hwnd);
 [DllImport("user32.dll",CharSet=CharSet.Unicode)] static extern int GetWindowText(IntPtr hwnd,System.Text.StringBuilder text,int max);
 [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr hwnd,out uint pid);
 [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
 [DllImport("kernel32.dll")] static extern uint GetCurrentThreadId();
 [DllImport("user32.dll")] static extern bool AttachThreadInput(uint a,uint b,bool attach);
 [StructLayout(LayoutKind.Sequential)] struct MSG {public IntPtr hwnd;public uint message;public UIntPtr wParam;public IntPtr lParam;public uint time;public int x,y;}
 [DllImport("user32.dll")] static extern bool PeekMessage(out MSG msg,IntPtr hwnd,uint min,uint max,uint remove);
 [DllImport("user32.dll")] static extern bool SetForegroundWindow(IntPtr hwnd);
 [DllImport("user32.dll")] static extern bool BringWindowToTop(IntPtr hwnd);
 [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr hwnd);
 [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hwnd,int cmd);
 [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr hwnd,uint msg,IntPtr wp,IntPtr lp);
 [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr hwnd,IntPtr dc,uint flags);
 [DllImport("user32.dll")] public static extern bool SetProcessDpiAwarenessContext(IntPtr value);
 public static IntPtr Find(uint wanted) {
  IntPtr result=IntPtr.Zero;long area=0;
  EnumWindows((h,p)=>{uint pid;GetWindowThreadProcessId(h,out pid);RECT r;
   if(pid==wanted&&IsWindowVisible(h)&&GetWindowRect(h,out r)) {var title=new System.Text.StringBuilder(512);GetWindowText(h,title,512);if(title.ToString()!="RDI Slint Prototype")return true;long a=(long)(r.R-r.L)*(r.B-r.T);if(a>area){area=a;result=h;}}
   return true;},IntPtr.Zero);return result;
 }
 public static uint ForegroundPid() {uint p;GetWindowThreadProcessId(GetForegroundWindow(),out p);return p;}
 public static bool Focus(IntPtr hwnd) {
  MSG message;PeekMessage(out message,IntPtr.Zero,0,0,0);
  uint ignored;uint current=GetCurrentThreadId(),fg=GetWindowThreadProcessId(GetForegroundWindow(),out ignored),target=GetWindowThreadProcessId(hwnd,out ignored);
  bool a=fg!=current&&AttachThreadInput(current,fg,true),b=target!=current&&target!=fg&&AttachThreadInput(current,target,true);
  try {BringWindowToTop(hwnd);SetForegroundWindow(hwnd);return GetForegroundWindow()==hwnd;}
  finally {if(b)AttachThreadInput(current,target,false);if(a)AttachThreadInput(current,fg,false);}
 }
}
'@
[P05Window]::SetProcessDpiAwarenessContext([IntPtr]::new(-4)) | Out-Null
$flags=@('--show-others','--all-playable','--scene-memory-probe','--scene-probe-unfocused','--scene-delay-ms=10000')
$exe='./target/release/rdi-slint-proto.exe'
switch ($Case -replace '-checked$','') {
 'old-static' { $exe='./evidence/bg-port/p0.5/p0-reference.exe';$flags=@('--show-others','--all-playable','--no-scene') }
 'static' {$flags+='--no-scene'}
 'pipelines' {$flags+='--scene-pipelines-only'}
 'live' {}
 'chunk' {$flags+='--scene-chunk-upload'}
 'packed' {$flags+='--scene-packed'}
 'msaa2' {$flags+='--scene-msaa=2'}
 'msaa1' {$flags+='--scene-msaa=1'}
 'lazy-drop' {$flags+=@('--scene-lazy-targets','--scene-drop-after=15')}
 'best' {$flags+=@('--scene-packed','--scene-msaa=1','--scene-lazy-targets')}
 default {throw "Unknown case: $Case"}
}
$prefix="evidence/bg-port/p0.5/controlled/$Case"
$p=Start-Process $exe -ArgumentList $flags -PassThru -RedirectStandardOutput "$prefix-app.log" -RedirectStandardError "$prefix-app.err.log"
$shell=New-Object -ComObject WScript.Shell
$start=Get-Date;$ready=$null;$handle=[IntPtr]::Zero;$minimized=$false;$resumed=$false;$lastPhase='startup'
function Record([string]$Phase) {
 $p.Refresh();if($p.HasExited){throw "Prototype exited early: $($p.ExitCode)"}
 $gpu=@(Get-CimInstance Win32_PerfFormattedData_GPUPerformanceCounters_GPUProcessMemory -ErrorAction SilentlyContinue | Where-Object Name -Like "pid_$($p.Id)_*")
 [pscustomobject]@{Case=$Case;Phase=$Phase;ProcessId=$p.Id;Time=(Get-Date).ToUniversalTime().ToString('o');Seconds=((Get-Date)-$start).TotalSeconds;WorkingSet=$p.WorkingSet64;Private=$p.PrivateMemorySize64;Dedicated=($gpu|Measure-Object DedicatedUsage -Sum).Sum;Shared=($gpu|Measure-Object SharedUsage -Sum).Sum;GpuInstances=$gpu.Count;ForegroundPid=[P05Window]::ForegroundPid();Focused=([P05Window]::GetForegroundWindow() -eq $handle);ActualMinimized=[P05Window]::IsIconic($handle)} | ConvertTo-Json -Compress | Add-Content "$prefix-memory.jsonl"
}
try {
 while ($true) {
  $p.Refresh();if($p.HasExited){throw "Prototype exited early: $($p.ExitCode)"}
  if(!$minimized){$actual=[P05Window]::Find($p.Id);if($actual -ne [IntPtr]::Zero){$handle=$actual}}
  if(!$minimized -and $handle -ne [IntPtr]::Zero -and [P05Window]::IsIconic($handle)) {
   Record 'unexpected-minimize'
   [P05Window]::ShowWindow($handle,4)|Out-Null
   if($ready){$ready=Get-Date;$resumed=$false;$lastPhase='loaded'}
  }
  $log=if(Test-Path "$prefix-app.log"){Get-Content "$prefix-app.log" -Raw}else{''}
  if(!$ready) {
   $done=if($Case -in @('old-static','static')){((Get-Date)-$start).TotalSeconds -ge 12}elseif($Case -eq 'pipelines'){$log -match 'Scene loader finished'}else{$log -match 'First live scene frame'}
   if($done){$ready=Get-Date;$lastPhase='loaded';Record 'loaded'}
   elseif($log -match 'Scene unavailable|Scene loader failed'){throw 'Scene experiment unavailable; inspect its error chain'}
   elseif(((Get-Date)-$start).TotalSeconds -gt 100){throw 'Readiness timed out; inspect logs (unsupported experiment is a result)'}
  }
  if($ready) {
   $elapsed=((Get-Date)-$ready).TotalSeconds
   if($Case -eq 'lazy-drop') {if($elapsed -ge 40){Record 'drop+25s';break}}
   elseif($elapsed -ge 60 -and !$minimized -and !$resumed){
    Record 'foreground60s'
    if($handle -ne [IntPtr]::Zero){
     $r=New-Object P05Window+RECT;[P05Window]::GetWindowRect($handle,[ref]$r)|Out-Null
     $bmp=New-Object System.Drawing.Bitmap ($r.R-$r.L),($r.B-$r.T);$g=[System.Drawing.Graphics]::FromImage($bmp);$dc=$g.GetHdc()
     $ok=[P05Window]::PrintWindow($handle,$dc,2);$g.ReleaseHdc($dc);if($ok){$bmp.Save((Join-Path $PWD "$prefix-glass.png"))};$g.Dispose();$bmp.Dispose()
     [P05Window]::ShowWindow($handle,6)|Out-Null
    }
    $minimized=$true;$lastPhase='minimized'
   }
   elseif($elapsed -ge 90 -and $minimized){Record 'minimized30s';[P05Window]::ShowWindow($handle,9)|Out-Null;$minimized=$false;$resumed=$true;$lastPhase='restored'}
   elseif($elapsed -ge 100 -and $resumed){Record 'restored10s';break}
  }
  Record $lastPhase
  Start-Sleep -Seconds 1
 }
 Set-Content "$prefix-measure.exit" 0
} catch {
 $_ | Out-String | Set-Content "$prefix-measure-error.log"
 Set-Content "$prefix-measure.exit" 1
} finally {
 if(!$p.HasExited){[P05Window]::PostMessage($handle,0x10,[IntPtr]::Zero,[IntPtr]::Zero)|Out-Null;if(!$p.WaitForExit(5000)){Stop-Process -Id $p.Id;$p.WaitForExit()}}
 Set-Content "$prefix-app.exit" $p.ExitCode
}
