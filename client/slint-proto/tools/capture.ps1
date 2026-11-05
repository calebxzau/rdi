param([string]$OutputName = 'capture.png')
Add-Type -AssemblyName System.Drawing
Add-Type @'
using System;
using System.Runtime.InteropServices;
public class CaptureNative {
 [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left,Top,Right,Bottom; }
 [DllImport("user32.dll")] public static extern bool SetProcessDpiAwarenessContext(IntPtr value);
 [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hwnd, out RECT rect);
 [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern IntPtr FindWindow(string cls,string title);
 [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr hwnd,IntPtr hdc,uint flags);
 [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hwnd);
 [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hwnd,int cmd);
}
'@
[CaptureNative]::SetProcessDpiAwarenessContext([IntPtr]::new(-4)) | Out-Null
$p=Get-Process rdi-slint-proto | Select-Object -First 1
$hwnd=$p.MainWindowHandle
if ($hwnd -eq [IntPtr]::Zero) { throw "Prototype window not found" }
Start-Sleep -Milliseconds 500
$r=New-Object CaptureNative+RECT
[CaptureNative]::GetWindowRect($hwnd,[ref]$r) | Out-Null
$b=New-Object System.Drawing.Bitmap ($r.Right-$r.Left),($r.Bottom-$r.Top)
$g=[System.Drawing.Graphics]::FromImage($b)
$dc=$g.GetHdc()
$ok=[CaptureNative]::PrintWindow($hwnd,$dc,2)
$g.ReleaseHdc($dc)
if (!$ok) { throw "PrintWindow failed" }
$b.Save((Join-Path $PWD "evidence/$OutputName"))
$g.Dispose();$b.Dispose()
$p | Select-Object Id,WorkingSet64,PrivateMemorySize64
