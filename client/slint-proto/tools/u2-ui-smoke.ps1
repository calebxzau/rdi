$ErrorActionPreference = 'Stop'
Set-Location "$PSScriptRoot/.."
$credentialLines = @(& wsl.exe -e cat /home/calebxzhou/.config/rdi/.test_usr_pwd)
if ($LASTEXITCODE -ne 0 -or $credentialLines.Count -lt 2) { throw 'Cannot read test credentials' }
try {
    $env:RDI_PROTO_USR = $credentialLines[0]
    $env:RDI_PROTO_PWD = $credentialLines[1]
    $app = Start-Process 'target/debug/rdi-slint-proto.exe' -PassThru -RedirectStandardOutput 'evidence/ui-port/u2/ui-stdout.log' -RedirectStandardError 'evidence/ui-port/u2/ui-stderr.log'
} finally {
    Remove-Item Env:RDI_PROTO_USR -ErrorAction SilentlyContinue
    Remove-Item Env:RDI_PROTO_PWD -ErrorAction SilentlyContinue
    $credentialLines = $null
}
try {
    Start-Sleep -Seconds 12
    if ($app.HasExited) { throw ('UI exited: ' + $app.ExitCode) }
    & "$PSScriptRoot/capture.ps1" 'ui-port/u2/menu-before.png'
    Add-Type @'
using System;
using System.Runtime.InteropServices;
public class U2FocusCheck {
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
}
'@
    if (-not ('P5Window' -as [type])) { Add-Type -TypeDefinition (Get-Content "$PSScriptRoot/P5Window.cs" -Raw) }
    [P5Window]::Focus($app.MainWindowHandle) | Out-Null
    Start-Sleep -Seconds 2
    $app.Refresh()
    if ([U2FocusCheck]::GetForegroundWindow() -ne $app.MainWindowHandle) { throw 'UI is not foreground; discard memory measurement' }
    'FocusedUiWorkingSetMiB=' + [math]::Round($app.WorkingSet64 / 1MB, 2)
    & "$PSScriptRoot/capture.ps1" 'ui-port/u2/menu-before.png' 
    $service = Get-CimInstance Win32_Process | Where-Object { $_.ParentProcessId -eq $app.Id -and $_.Name -match 'java' }
    if (@($service).Count -ne 1) { throw 'Expected one service child' }
    'ServiceBefore=' + $service.ProcessId
    'ServiceWorkingSetMiB=' + [math]::Round((Get-Process -Id $service.ProcessId).WorkingSet64 / 1MB, 2)
    Stop-Process -Id $service.ProcessId -Force
    Start-Sleep -Seconds 12
    $replacement = Get-CimInstance Win32_Process | Where-Object { $_.ParentProcessId -eq $app.Id -and $_.Name -match 'java' }
    if (@($replacement).Count -ne 1 -or $replacement.ProcessId -eq $service.ProcessId) { throw 'Service did not restart' }
    'ServiceRestart=pass'
    & "$PSScriptRoot/capture.ps1" 'ui-port/u2/menu-after-restart.png'
    $app.CloseMainWindow() | Out-Null
    if (-not $app.WaitForExit(10000)) { throw 'UI close timed out' }
    'UiExit=' + $app.ExitCode
} finally {
    if (-not $app.HasExited) { $app.Kill(); $app.WaitForExit() }
    Get-CimInstance Win32_Process | Where-Object { $_.ParentProcessId -eq $app.Id -and $_.Name -match 'java' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
}
