param([ValidateSet('baseline', 'final', 'final-fxc', 'baseline-static', 'final-static')][string]$Case, [string]$Tag)
. "$PSScriptRoot/p5-common.ps1"
Require-Credentials
if ($Case -eq 'final-fxc') { $env:WGPU_DX12_COMPILER = 'fxc' } else { Remove-Item Env:WGPU_DX12_COMPILER -ErrorAction SilentlyContinue }
$dir = 'evidence/bg-port/startup/measure'
New-Item -ItemType Directory -Force $dir | Out-Null
$prefix = "$dir/$Tag"
if (Test-Path "$prefix-memory.jsonl") { throw 'Existing measurement output; use a new tag to preserve evidence' }
$exe = if ($Case -like 'baseline*') { './evidence/bg-port/startup/pre-change/baseline-p4b.exe' } else { './evidence/bg-port/startup/controls/rdi-slint-proto.exe' }
$flags = @('--show-others', '--all-playable', '--scene-memory-probe', '--scene-probe-hold-ms=0')
$static = $Case -like '*-static'
if ($static) { $flags += '--no-scene' }
$p = Start-Process $exe -ArgumentList $flags -PassThru -RedirectStandardOutput "$prefix-app.log" -RedirectStandardError "$prefix-app.err.log"
$start = Get-Date; $handle = [IntPtr]::Zero; $ready = $null
try {
    while ($true) {
        $p.Refresh(); if ($p.HasExited) { throw 'App exited before endpoint' }
        if ($handle -eq [IntPtr]::Zero) { $handle = [P5Window]::Find($p.Id) }
        if ($handle -ne [IntPtr]::Zero) { Require-Focus $handle }
        $log = if (Test-Path "$prefix-app.log") { Get-Content "$prefix-app.log" -Raw } else { '' }
        if ($log -match 'Menu login/load failed|Scene unavailable|Scene loader failed|terminal static fallback') { throw 'Readiness or fallback failure' }
        if (!$ready) {
            $done = if ($static) { $log -match 'First frame' } else { $log -match 'First live scene frame' -and $log -match 'Account player appearance ready' -and $log -match 'Crowd skins finished' }
            if ($done -and $log -match 'Menu data loaded') { $ready = Get-Date }
            elseif (((Get-Date) - $start).TotalSeconds -gt 120) { throw 'Readiness timeout' }
        }
        if ($ready -and ((Get-Date) - $ready).TotalSeconds -ge 60) {
            Require-Focus $handle
            $sample = Process-Sample $p $handle 'foreground60s' $start
            if (!$sample.Focused -or $sample.ActualMinimized) { throw 'Visible endpoint state mismatch' }
            $sample | ConvertTo-Json -Depth 6 -Compress | Add-Content "$prefix-memory.jsonl"
            Save-Window $handle "$prefix-glass.png"
            break
        }
        Start-Sleep -Milliseconds 700
    }
    Set-Content "$prefix-measure.exit" 0
} catch {
    $_ | Out-String | Set-Content "$prefix-measure-error.log"
    Set-Content "$prefix-measure.exit" 1
    throw
} finally {
    Stop-Prototype $p $handle
}
