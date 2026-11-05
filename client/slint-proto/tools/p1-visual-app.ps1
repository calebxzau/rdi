$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
Add-Type -AssemblyName System.Drawing
Add-Type @'
using System;
using System.Text;
using System.Runtime.InteropServices;
public class P1VisualWindow {
    [StructLayout(LayoutKind.Sequential)] public struct RECT {public int L,T,R,B;}
    public delegate bool WindowEnum(IntPtr hwnd,IntPtr p);
    [DllImport("user32.dll")] static extern bool EnumWindows(WindowEnum callback,IntPtr p);
    [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr hwnd,out uint pid);
    [DllImport("user32.dll",CharSet=CharSet.Unicode)] static extern int GetWindowText(IntPtr hwnd,StringBuilder text,int max);
    [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr hwnd);
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hwnd,out RECT rect);
    [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr hwnd,IntPtr dc,uint flags);
    [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr hwnd,uint msg,IntPtr wp,IntPtr lp);
    [DllImport("user32.dll")] public static extern bool SetProcessDpiAwarenessContext(IntPtr value);
    public static IntPtr Find(uint wanted) {
        IntPtr result=IntPtr.Zero;
        EnumWindows((h,p)=>{uint pid;GetWindowThreadProcessId(h,out pid);var title=new StringBuilder(512);GetWindowText(h,title,512);
            if(pid==wanted && IsWindowVisible(h) && title.ToString()=="RDI Slint Prototype")result=h;return true;},IntPtr.Zero);
        return result;
    }
}
'@
[P1VisualWindow]::SetProcessDpiAwarenessContext([IntPtr]::new(-4)) | Out-Null
$root='evidence/bg-port/p1/visual'
$process=Start-Process ./target/release/rdi-slint-proto.exe -ArgumentList @('--show-others','--all-playable','--scene-probe-unfocused') -PassThru -RedirectStandardOutput "$root/app.log" -RedirectStandardError "$root/app.err.log"
$handle=[IntPtr]::Zero
try {
    $deadline=(Get-Date).AddSeconds(40)
    do {
        Start-Sleep -Milliseconds 500
        $process.Refresh()
        if($process.HasExited){throw "Prototype exited: $($process.ExitCode)"}
        $handle=[P1VisualWindow]::Find($process.Id)
        $log=Get-Content "$root/app.log" -Raw
        if($log -match 'Scene unavailable|Scene loader failed'){throw 'Scene failed; inspect the log'}
    } while(($handle -eq [IntPtr]::Zero -or $log -notmatch 'First live scene frame') -and (Get-Date) -lt $deadline)
    if($handle -eq [IntPtr]::Zero -or $log -notmatch 'First live scene frame'){throw 'Scene readiness timed out'}
    Start-Sleep -Seconds 3
    $rect=New-Object P1VisualWindow+RECT
    [P1VisualWindow]::GetWindowRect($handle,[ref]$rect) | Out-Null
    $bitmap=New-Object System.Drawing.Bitmap ($rect.R-$rect.L),($rect.B-$rect.T)
    $graphics=[System.Drawing.Graphics]::FromImage($bitmap)
    $dc=$graphics.GetHdc()
    $ok=[P1VisualWindow]::PrintWindow($handle,$dc,2)
    $graphics.ReleaseHdc($dc)
    if(!$ok){throw 'PrintWindow failed'}
    $bitmap.Save((Join-Path $PWD "$root/live-glass.png"))
    $graphics.Dispose();$bitmap.Dispose()
    Set-Content "$root/app-capture.exit" 0
} finally {
    if(!$process.HasExited){
        [P1VisualWindow]::PostMessage($handle,0x10,[IntPtr]::Zero,[IntPtr]::Zero) | Out-Null
        if(!$process.WaitForExit(5000)){throw 'Preview window did not close after WM_CLOSE'}
    }
}
