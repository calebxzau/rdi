# Lists each display with its mode, so a measurement can name the display it ran on.
Add-Type -AssemblyName System.Windows.Forms
Add-Type -TypeDefinition @"
using System;using System.Runtime.InteropServices;
public static class U1Dpi{[DllImport("user32.dll")]public static extern bool SetProcessDpiAwarenessContext(IntPtr v);}
"@
[U1Dpi]::SetProcessDpiAwarenessContext([IntPtr]::new(-4))|Out-Null
[System.Windows.Forms.Screen]::AllScreens | ForEach-Object { "$($_.DeviceName) primary=$($_.Primary) bounds=$($_.Bounds)" }
Get-CimInstance -Namespace root\wmi -ClassName WmiMonitorListedSupportedSourceModes -ErrorAction SilentlyContinue | ForEach-Object { "monitor $($_.InstanceName)" }
Get-CimInstance Win32_DesktopMonitor | ForEach-Object { "desktop-monitor $($_.Name) $($_.ScreenWidth)x$($_.ScreenHeight)" }
Get-CimInstance Win32_VideoController | ForEach-Object { "adapter $($_.Name) $($_.CurrentHorizontalResolution)x$($_.CurrentVerticalResolution)@$($_.CurrentRefreshRate)" }
