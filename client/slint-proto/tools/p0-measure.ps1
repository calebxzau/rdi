# Paired foreground runs. Never writes credentials or modifies the user's environment.
param([ValidateSet('static','scene')][string]$Mode = 'scene', [string]$Tag = 'p0')
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
Add-Type -AssemblyName System.Drawing
Add-Type @'
using System;
using System.Runtime.InteropServices;
public class P0Native {
 [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left,Top,Right,Bottom; }
 [DllImport("user32.dll")] public static extern bool SetProcessDpiAwarenessContext(IntPtr value);
 [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hwnd,out RECT rect);
 [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr hwnd,IntPtr dc,uint flags);
 [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hwnd);
 [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
 [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hwnd,IntPtr pid);
 [DllImport("kernel32.dll")] public static extern uint GetCurrentThreadId();
 [DllImport("user32.dll")] public static extern bool AttachThreadInput(uint a,uint b,bool attach);
 public static bool Focus(IntPtr hwnd) {
  uint current=GetCurrentThreadId(), foreground=GetWindowThreadProcessId(GetForegroundWindow(),IntPtr.Zero);
  bool attached=current!=foreground && AttachThreadInput(current,foreground,true);
  try { SetForegroundWindow(hwnd); return GetForegroundWindow()==hwnd; }
  finally { if(attached) AttachThreadInput(current,foreground,false); }
 }
 [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hwnd,int cmd);
 [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr hwnd,uint msg,IntPtr wp,IntPtr lp);
}
'@
[P0Native]::SetProcessDpiAwarenessContext([IntPtr]::new(-4)) | Out-Null
$arguments = @('--show-others','--all-playable')
if ($Mode -eq 'static') { $arguments += '--no-scene' }
$p = Start-Process ./target/release/rdi-slint-proto.exe -ArgumentList $arguments -PassThru -RedirectStandardOutput "evidence/bg-port/${Tag}-$Mode-app.log" -RedirectStandardError "evidence/bg-port/${Tag}-$Mode-app.err.log"
function Sample([string]$Phase) {
 $p.Refresh()
 if ($p.HasExited) { throw "Prototype exited: $($p.ExitCode)" }
 $gpu = Get-CimInstance Win32_PerfFormattedData_GPUPerformanceCounters_GPUProcessMemory -ErrorAction SilentlyContinue | Where-Object Name -Like "pid_$($p.Id)_*"
 [pscustomobject]@{Mode=$Mode;Phase=$Phase;ProcessId=$p.Id;Time=(Get-Date).ToString('o');WorkingSet=$p.WorkingSet64;Private=$p.PrivateMemorySize64;Dedicated=($gpu | Measure-Object DedicatedUsage -Sum).Sum;Shared=($gpu | Measure-Object SharedUsage -Sum).Sum;CredentialsAvailable=([bool]$env:RDI_PROTO_USR -and [bool]$env:RDI_PROTO_PWD);Focused=([P0Native]::GetForegroundWindow() -eq $p.MainWindowHandle)} | ConvertTo-Json -Compress | Add-Content "evidence/bg-port/${Tag}-$Mode-memory.jsonl"
}
try {
 # Focus as soon as Slint creates the window so startup does not include an artificial inactive wait.
 for ($attempt = 0; $attempt -lt 100; $attempt++) {
  $p.Refresh()
  if ($p.HasExited) { throw "Prototype exited before its window appeared: $($p.ExitCode)" }
  if ($p.MainWindowHandle -ne [IntPtr]::Zero) { [P0Native]::Focus($p.MainWindowHandle) | Out-Null; break }
  Start-Sleep -Milliseconds 50
 }
 Start-Sleep -Seconds 2
 $p.Refresh(); [P0Native]::Focus($p.MainWindowHandle) | Out-Null
 Start-Sleep -Seconds 9
 $p.Refresh(); $handle=$p.MainWindowHandle
 [P0Native]::Focus($handle) | Out-Null
 Sample 'loaded11s'
 Start-Sleep -Seconds 60
 Sample 'foreground60s'
 # A window-only capture; no desktop or other applications are captured.
 $r = New-Object P0Native+RECT
 [P0Native]::GetWindowRect($handle,[ref]$r) | Out-Null
 $bitmap=New-Object System.Drawing.Bitmap ($r.Right-$r.Left),($r.Bottom-$r.Top)
 $g=[System.Drawing.Graphics]::FromImage($bitmap); $dc=$g.GetHdc()
 $ok=[P0Native]::PrintWindow($handle,$dc,2);$g.ReleaseHdc($dc)
 if (!$ok) { throw 'PrintWindow failed' }
 $bitmap.Save((Join-Path $PWD "evidence/bg-port/${Tag}-$Mode-glass.png"));$g.Dispose();$bitmap.Dispose()
 [P0Native]::ShowWindow($handle,6) | Out-Null
 Start-Sleep -Seconds 30
 Sample 'minimized30s'
 [P0Native]::ShowWindow($handle,9) | Out-Null
 [P0Native]::Focus($handle) | Out-Null
 Start-Sleep -Seconds 10
 Sample 'restored10s'
} finally {
 if (!$p.HasExited) {
  [P0Native]::PostMessage($p.MainWindowHandle,0x10,[IntPtr]::Zero,[IntPtr]::Zero) | Out-Null
  $p.WaitForExit(5000) | Out-Null
 }
}
if ($Mode -eq 'scene' -and $Tag -eq 'p0-authenticated') {
 & pwsh.exe -NoProfile -File tools/p0-startup.ps1
}
