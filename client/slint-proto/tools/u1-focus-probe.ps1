# Starts the proof window for a few seconds and reports whether it can really take the foreground.
. "$PSScriptRoot/p5-common.ps1"
$out = "$env:TEMP/rdi-u1-focus-probe"; New-Item -ItemType Directory -Force $out | Out-Null
$log = "$out/app.log"
$p = Start-Process ./target/release/rdi-slint-proto.exe -PassThru -ArgumentList @('--ui-proof','--scene-memory-probe','--scene-probe-hold-ms=0','--scene-steve-only') -RedirectStandardOutput $log -RedirectStandardError "$out/app.err"
try {
    $h = [IntPtr]::Zero
    foreach ($i in 1..40) { Start-Sleep -Milliseconds 250; $h = [P5Window]::Find($p.Id); if ($h -ne [IntPtr]::Zero) { break } }
    "window handle $h"
    "before: foreground=$([P5Window]::GetForegroundWindow()) pid=$([P5Window]::ForegroundPid())"
    foreach ($i in 1..8) {
        $a = [P5Window]::Activate($h)
        if ($i -eq 4) { [P5Window]::Reacquire($h) }
        Start-Sleep -Milliseconds 700
        $text = [regex]::Replace((Get-Content $log -Raw), '\x1b\[[0-9;]*m', '')
        $beats = [regex]::Matches($text, 'Runtime probe data=(\{[^\r\n]+\})')
        $focused = if ($beats.Count) { ($beats[$beats.Count-1].Groups[1].Value | ConvertFrom-Json).focused } else { 'none' }
        "try $i activate=$a foreground=$([P5Window]::GetForegroundWindow()) isOurs=$([P5Window]::GetForegroundWindow() -eq $h) fgPid=$([P5Window]::ForegroundPid()) appFocused=$focused"
    }
} finally { Stop-Prototype $p $h }
quser 2>&1
