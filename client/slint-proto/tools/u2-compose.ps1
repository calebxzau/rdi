$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path "$PSScriptRoot/../../..").Path
$cp = (Get-Content "$repo/client/ui/build/u2-runtime-classpath.txt" -Raw).Trim()
$argumentsFile = "$repo/client/ui/build/u2-compose.args"
$escaped = $cp.Replace('\','\\').Replace('"','\"')
[IO.File]::WriteAllText($argumentsFile, "-Drdi.noUpdate=true`n-cp`n`"$escaped`"`ncalebxzau.rdi.client.MainKt`n", [Text.UTF8Encoding]::new($false))
$app = Start-Process (Get-Command java).Source -ArgumentList "@$argumentsFile" -WorkingDirectory "$repo/client/ui/run" -PassThru -RedirectStandardOutput "$repo/client/slint-proto/evidence/ui-port/u2/compose-stdout.log" -RedirectStandardError "$repo/client/slint-proto/evidence/ui-port/u2/compose-stderr.log"
try {
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    do {
        Start-Sleep -Milliseconds 500
        $app.Refresh()
        if ($app.HasExited) { throw ('Compose exited before window: ' + $app.ExitCode) }
    } while ($app.MainWindowHandle -eq [IntPtr]::Zero -and [DateTime]::UtcNow -lt $deadline)
    if ($app.MainWindowHandle -eq [IntPtr]::Zero) { throw 'No Compose window' }
    Start-Sleep -Seconds 8
    Add-Type -AssemblyName System.Drawing
    Add-Type @'
using System;
using System.Runtime.InteropServices;
public class U2ComposeCapture {
 [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left,Top,Right,Bottom; }
 [DllImport("user32.dll")] public static extern bool SetProcessDpiAwarenessContext(IntPtr value);
 [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hwnd,out RECT rect);
 [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr hwnd,IntPtr dc,uint flags);
}
'@
    [U2ComposeCapture]::SetProcessDpiAwarenessContext([IntPtr]::new(-4)) | Out-Null
    $r = [U2ComposeCapture+RECT]::new()
    [U2ComposeCapture]::GetWindowRect($app.MainWindowHandle,[ref]$r) | Out-Null
    $bitmap = [Drawing.Bitmap]::new($r.Right-$r.Left,$r.Bottom-$r.Top)
    $graphics = [Drawing.Graphics]::FromImage($bitmap)
    $dc = $graphics.GetHdc()
    try { if (-not [U2ComposeCapture]::PrintWindow($app.MainWindowHandle,$dc,2)) { throw 'Compose capture failed' } }
    finally { $graphics.ReleaseHdc($dc) }
    try { $bitmap.Save("$repo/client/slint-proto/evidence/ui-port/u2/compose-runtime.png") }
    finally { $graphics.Dispose(); $bitmap.Dispose() }
    'ComposeWindow=' + $app.MainWindowTitle
    $app.CloseMainWindow() | Out-Null
    if (-not $app.WaitForExit(10000)) { throw 'Compose close timed out' }
    'ComposeExit=' + $app.ExitCode
} finally {
    if (-not $app.HasExited) { $app.Kill(); $app.WaitForExit() }
}
