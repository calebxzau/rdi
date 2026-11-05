param([string]$Tag='window-input')
. "$PSScriptRoot/p5-common.ps1"
Add-Type -AssemblyName System.Windows.Forms
Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class U1Mouse {
 [DllImport("user32.dll")] public static extern bool SetCursorPos(int x,int y);
 [DllImport("user32.dll")] public static extern void mouse_event(uint flags,uint dx,uint dy,uint data,UIntPtr extra);
 public static void Down(){mouse_event(2,0,0,0,UIntPtr.Zero);}
 public static void Up(){mouse_event(4,0,0,0,UIntPtr.Zero);}
}
'@
$out="evidence/ui-port/u1/$Tag"
if(Test-Path $out){throw 'Select a new tag'}
New-Item -ItemType Directory -Force $out|Out-Null
$prefix="$out/run"
$p=Start-Process ./target/release/rdi-slint-proto.exe -PassThru -ArgumentList @('--ui-proof','--scene-memory-probe','--scene-probe-hold-ms=0') -RedirectStandardOutput "$prefix-app.log" -RedirectStandardError "$prefix-app.err"
$h=[IntPtr]::Zero
function Rect { $r=New-Object P5Window+RECT;[P5Window]::GetWindowRect($h,[ref]$r)|Out-Null;return $r }
function Move-Pointer([int]$X,[int]$Y){[U1Mouse]::SetCursorPos($X,$Y)|Out-Null;Start-Sleep -Milliseconds 100}
function Click([int]$X,[int]$Y){Move-Pointer $X $Y;[U1Mouse]::Down();Start-Sleep -Milliseconds 80;[U1Mouse]::Up();Start-Sleep -Milliseconds 600}
try {
 Start-Sleep -Seconds 4;$h=[P5Window]::Find($p.Id);Require-Focus $h
 $before=Rect
 # Window starts at native scale 2 on the owner's 780M desktop.
 $scale=2
 Move-Pointer ($before.L+300*$scale) ($before.T+20*$scale)
 [U1Mouse]::Down();Start-Sleep -Milliseconds 100
 Move-Pointer ($before.L+300*$scale+60) ($before.T+20*$scale+50)
 [U1Mouse]::Up();Start-Sleep -Seconds 1
 $after=Rect
 $result=@{drag_dx=$after.L-$before.L;drag_dy=$after.T-$before.T}
 # Double click title bar.
 Move-Pointer ($after.L+300*$scale) ($after.T+20*$scale)
 [U1Mouse]::Down();[U1Mouse]::Up();Start-Sleep -Milliseconds 80
 [U1Mouse]::Down();[U1Mouse]::Up();Start-Sleep -Seconds 1
 $max=Rect
 Save-Window $h "$out/title-double-click.png"
 $result.double_click_width=$max.R-$max.L
 # Restore through native API before clicking the custom maximize button.
 [P5Window]::ShowWindow($h,9)|Out-Null;Start-Sleep -Seconds 1
 $r=Rect;Click ($r.R-93*$scale) ($r.T+20*$scale)
 $r=Rect;$result.maximize_button_width=$r.R-$r.L
 [P5Window]::ShowWindow($h,9)|Out-Null;Start-Sleep -Seconds 1
 $r=Rect;Click ($r.R-139*$scale) ($r.T+20*$scale)
 $result.minimize_button=[P5Window]::IsIconic($h)
 [P5Window]::ShowWindow($h,9)|Out-Null;Require-Focus $h
 # Empty Enter should validate without making an API call.
 $r=Rect;Click ($r.L+100*$scale) ($r.T+140*$scale)
 [System.Windows.Forms.SendKeys]::SendWait("{ENTER}")
 Start-Sleep -Seconds 1
 Save-Window $h "$out/empty-enter.png"
 # Exercise resize via the bottom-right handle.
 $r=Rect
 Move-Pointer ($r.R-3) ($r.B-3);[U1Mouse]::Down();Start-Sleep -Milliseconds 100
 Move-Pointer ($r.R-150) ($r.B-80);[U1Mouse]::Up();Start-Sleep -Seconds 1
 $resized=Rect;$result.resize_width=$resized.R-$resized.L;$result.resize_height=$resized.B-$resized.T
 Save-Window $h "$out/resize-handle.png"
 $r=Rect;Click ($r.R-47*$scale) ($r.T+20*$scale)
 $result.close_button_exited=$p.WaitForExit(5000)
 if($result.close_button_exited){$result.exit=$p.ExitCode}
 $result|ConvertTo-Json > "$out/result.json"
} finally {if(!$p.HasExited){Stop-Prototype $p $h}}
