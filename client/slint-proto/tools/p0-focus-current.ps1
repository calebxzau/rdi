param([Parameter(Mandatory=$true)][int]$ProcessId)
$ErrorActionPreference = 'Stop'
Add-Type @'
using System;
using System.Runtime.InteropServices;
public class P0Focus {
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
}
'@
Start-Sleep -Seconds 2
$p = Get-Process rdi-slint-proto | Where-Object Id -eq $ProcessId
if ($p) { [P0Focus]::Focus($p.MainWindowHandle) }
