# Three paired startup observations after the memory runs. Credentials stay in inherited environment.
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
Add-Type @'
using System;
using System.Runtime.InteropServices;
public class P0Close {
 [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr hwnd,uint msg,IntPtr wp,IntPtr lp);
}
'@
for ($run = 1; $run -le 3; $run++) {
 foreach ($mode in @('static','scene')) {
  $arguments = @('--show-others','--all-playable')
  if ($mode -eq 'static') { $arguments += '--no-scene' }
  if ($run -eq 1 -and $mode -eq 'scene') { $arguments += @('--log','info,wgpu_core=debug') }
  $p = Start-Process ./target/release/rdi-slint-proto.exe -ArgumentList $arguments -PassThru -RedirectStandardOutput "evidence/bg-port/p0-startup-$mode-$run.log" -RedirectStandardError "evidence/bg-port/p0-startup-$mode-$run.err.log"
  try {
   for ($attempt = 0; $attempt -lt 100; $attempt++) {
    $p.Refresh()
    if ($p.HasExited) { throw "Prototype exited: $($p.ExitCode)" }
    if ($p.MainWindowHandle -ne [IntPtr]::Zero) { break }
    Start-Sleep -Milliseconds 50
   }
   & ./tools/p0-focus-current.ps1 -ProcessId $p.Id | Out-Null
   Start-Sleep -Seconds 3
   if ($run -eq 3 -and $mode -eq 'scene') { & ./tools/capture.ps1 -OutputName bg-port/p0-final-glass.png | Out-Null }
  } finally {
   if (!$p.HasExited) {
    $p.Refresh()
    [P0Close]::PostMessage($p.MainWindowHandle,0x10,[IntPtr]::Zero,[IntPtr]::Zero) | Out-Null
    if (!$p.WaitForExit(5000)) { Stop-Process -Id $p.Id; $p.WaitForExit() }
   }
  }
 }
}
