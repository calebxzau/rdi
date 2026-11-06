param([switch]$LocalCore, [ValidateSet('Start','Capture','Join','Stop','Duplicate','CancelDialog','CloseRequest','ConfirmClose')][string]$Action = 'Start')
$ErrorActionPreference = 'Stop'
Set-Location "$PSScriptRoot/.."
Add-Type -AssemblyName System.Drawing
if (-not ('U2GameNative' -as [type])) {
Add-Type @'
using System;
using System.Runtime.InteropServices;
public class U2GameNative {
 [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left,Top,Right,Bottom; }
 [DllImport("user32.dll")] public static extern bool SetProcessDpiAwarenessContext(IntPtr value);
 [DllImport("user32.dll")] public static extern bool GetClientRect(IntPtr hwnd, out RECT rect);
 [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hwnd, out RECT rect);
 [DllImport("user32.dll")] public static extern uint GetDpiForWindow(IntPtr hwnd);
 [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr hwnd,uint msg,IntPtr w,IntPtr l);
 [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr hwnd,IntPtr dc,uint flags);
 [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hwnd);
}
'@
}
[U2GameNative]::SetProcessDpiAwarenessContext([IntPtr]::new(-4)) | Out-Null
function Click-Window($window, [int]$x, [int]$y) {
    $point = [IntPtr]::new($x -bor ($y -shl 16))
    [U2GameNative]::PostMessage($window,0x200,[IntPtr]::Zero,$point) | Out-Null
    [U2GameNative]::PostMessage($window,0x201,[IntPtr]::new(1),$point) | Out-Null
    Start-Sleep -Milliseconds 150
    [U2GameNative]::PostMessage($window,0x202,[IntPtr]::Zero,$point) | Out-Null
}
function Capture-Window($window, $name) {
    $rect = [U2GameNative+RECT]::new()
    [U2GameNative]::GetWindowRect($window,[ref]$rect) | Out-Null
    $bitmap = [Drawing.Bitmap]::new($rect.Right-$rect.Left,$rect.Bottom-$rect.Top)
    $graphics = [Drawing.Graphics]::FromImage($bitmap)
    $dc = $graphics.GetHdc()
    try {
        if (-not [U2GameNative]::PrintWindow($window,$dc,2)) { throw 'PrintWindow failed' }
    } finally { $graphics.ReleaseHdc($dc) }
    try { $bitmap.Save((Join-Path $PWD "evidence/ui-port/u2/$name.png")) }
    finally { $graphics.Dispose(); $bitmap.Dispose() }
}
$statePath = 'evidence/ui-port/u2/game-run.json'
if ($Action -eq 'Start') {
    if ($LocalCore) { $env:RDI_SERVICE_CORE_JAR = (Resolve-Path '../mc/1.21.1-neoforge/build/libs/rdi-5-mc-client-1.21.1-neoforge.jar').Path }
    $credentialLines = @(& wsl.exe -e cat /home/calebxzhou/.config/rdi/.test_usr_pwd)
    if ($LASTEXITCODE -ne 0 -or $credentialLines.Count -lt 2) { throw 'Cannot read test credentials' }
    try {
        $env:RDI_PROTO_USR = $credentialLines[0]; $env:RDI_PROTO_PWD = $credentialLines[1]
        $app = Start-Process 'target/debug/rdi-slint-proto.exe' -PassThru -RedirectStandardOutput 'evidence/ui-port/u2/game-ui-stdout.log' -RedirectStandardError 'evidence/ui-port/u2/game-ui-stderr.log'
    } finally {
        Remove-Item Env:RDI_PROTO_USR -ErrorAction SilentlyContinue
        Remove-Item Env:RDI_PROTO_PWD -ErrorAction SilentlyContinue
        $credentialLines = $null
        Remove-Item Env:RDI_SERVICE_CORE_JAR -ErrorAction SilentlyContinue
    }
    @{app=$app.Id} | ConvertTo-Json | Set-Content $statePath
    $deadline = [DateTime]::UtcNow.AddSeconds(40)
    do {
        if ($app.HasExited) { throw 'UI exited before menu' }
        if ((Get-Content 'evidence/ui-port/u2/game-ui-stdout.log' -Raw -ErrorAction SilentlyContinue) -match 'Menu data loaded') { break }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    if ([DateTime]::UtcNow -ge $deadline) { throw 'Menu not ready' }
    $app.Refresh()
    $rect = [U2GameNative+RECT]::new()
    [U2GameNative]::GetClientRect($app.MainWindowHandle,[ref]$rect) | Out-Null
    $scale = [U2GameNative]::GetDpiForWindow($app.MainWindowHandle) / 96.0
    $height = ($rect.Bottom-$rect.Top) / $scale
    $gridY = 40 + ($height - 40 - 434) / 2
    [U2GameNative]::SetForegroundWindow($app.MainWindowHandle) | Out-Null
    Click-Window $app.MainWindowHandle ([int](79*$scale)) ([int](($gridY+79)*$scale))
    'SlintRoomClick=sent'
    Start-Sleep -Seconds 10
}
$state = Get-Content $statePath -Raw | ConvertFrom-Json
$app = Get-Process -Id $state.app
$service = Get-CimInstance Win32_Process | Where-Object { $_.ParentProcessId -eq $app.Id -and $_.Name -match 'java' } | Select-Object -First 1
$game = if ($service) { Get-CimInstance Win32_Process | Where-Object { $_.ParentProcessId -eq $service.ProcessId -and $_.Name -match 'java' } | ForEach-Object { Get-Process -Id $_.ProcessId } | Where-Object { $_.MainWindowHandle -ne [IntPtr]::Zero } | Select-Object -First 1 }
if ($Action -in @('Duplicate','CancelDialog')) {
    $rect = [U2GameNative+RECT]::new()
    [U2GameNative]::GetClientRect($app.MainWindowHandle,[ref]$rect) | Out-Null
    $scale = [U2GameNative]::GetDpiForWindow($app.MainWindowHandle) / 96.0
    $width = ($rect.Right-$rect.Left)/$scale; $height = ($rect.Bottom-$rect.Top)/$scale
    [U2GameNative]::SetForegroundWindow($app.MainWindowHandle) | Out-Null
    if ($Action -eq 'Duplicate') {
        $gridY = 40 + ($height - 40 - 434) / 2
        Click-Window $app.MainWindowHandle ([int](79*$scale)) ([int](($gridY+79)*$scale))
        Start-Sleep -Seconds 2
    } else {
        Click-Window $app.MainWindowHandle ([int](($width/2-116)*$scale)) ([int](($height/2+60)*$scale))
    }
}
if ($Action -eq 'Join') {
    if (-not $game) { throw 'No game window' }
    $rect = [U2GameNative+RECT]::new()
    [U2GameNative]::GetClientRect($game.MainWindowHandle,[ref]$rect) | Out-Null
    [U2GameNative]::SetForegroundWindow($game.MainWindowHandle) | Out-Null
    Click-Window $game.MainWindowHandle ([int](($rect.Right-$rect.Left)/2)) 24
    'JoinClick=sent'
}
if ($Action -eq 'Stop') {
    $rect = [U2GameNative+RECT]::new()
    [U2GameNative]::GetClientRect($app.MainWindowHandle,[ref]$rect) | Out-Null
    $scale = [U2GameNative]::GetDpiForWindow($app.MainWindowHandle) / 96.0
    Click-Window $app.MainWindowHandle ([int]($rect.Right-100*$scale)) ([int]($rect.Bottom-28*$scale))
    'StopClick=sent'
}
if ($Action -eq 'CloseRequest') { $app.CloseMainWindow() | Out-Null; Start-Sleep -Seconds 1 }
if ($Action -eq 'ConfirmClose') {
    $rect = [U2GameNative+RECT]::new()
    [U2GameNative]::GetClientRect($app.MainWindowHandle,[ref]$rect) | Out-Null
    $scale = [U2GameNative]::GetDpiForWindow($app.MainWindowHandle) / 96.0
    $width = ($rect.Right-$rect.Left)/$scale; $height = ($rect.Bottom-$rect.Top)/$scale
    Click-Window $app.MainWindowHandle ([int](($width/2+108)*$scale)) ([int](($height/2+60)*$scale))
    if (-not $app.WaitForExit(15000)) { throw 'UI did not close' }
    'UiExit=' + $app.ExitCode
    exit
}
if ($game) { Capture-Window $game.MainWindowHandle "game-$Action"; 'GameWindow=' + $game.MainWindowTitle }
Capture-Window $app.MainWindowHandle "slint-game-$Action"
'AppPid=' + $app.Id
if ($service) { 'ServicePid=' + $service.ProcessId }
if ($game) { 'GamePid=' + $game.Id }
