param([double]$Scale=2,[switch]$Close)
. "$PSScriptRoot/p5-common.ps1"
Add-Type -AssemblyName System.Windows.Forms
Add-Type @'
using System;
using System.Runtime.InteropServices;
public class U4aComposeMouse {
 [DllImport("user32.dll")] public static extern void mouse_event(uint flags,uint x,uint y,uint data,UIntPtr extra);
 [DllImport("user32.dll")] public static extern bool SetCursorPos(int x,int y);
}
'@
$out='evidence/ui-port/u4a/compose-menu'
$run=Get-Content "$out/run.json" -Raw|ConvertFrom-Json
$app=Get-Process -Id $run.app
$handle=$app.MainWindowHandle
[P5Window]::SetWindowPos($handle,[IntPtr]::Zero,40,40,(1280*$Scale),(720*$Scale),20)|Out-Null
[P5Window]::Activate($handle)|Out-Null
Start-Sleep -Milliseconds 500
$rect=New-Object P5Window+RECT
[P5Window]::GetWindowRect($handle,[ref]$rect)|Out-Null
[U4aComposeMouse]::SetCursorPos(($rect.L+[int](700*$Scale)),($rect.T+[int](84*$Scale)))|Out-Null
Start-Sleep -Milliseconds 400
[U4aComposeMouse]::mouse_event(2,0,0,0,[UIntPtr]::Zero)
Start-Sleep -Milliseconds 100
[U4aComposeMouse]::mouse_event(4,0,0,0,[UIntPtr]::Zero)
Start-Sleep -Seconds 2
Save-Window $handle "$out/installed-scale$Scale.png"
if($Close){$app.CloseMainWindow()|Out-Null;if(!$app.WaitForExit(5000)){Stop-Process -Id $app.Id}}
