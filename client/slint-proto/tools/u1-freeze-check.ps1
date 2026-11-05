# Holds and resumes the scene on the proof screen: picture, animation clock, idle CPU and memory.
param([string]$Tag = 'freeze-check')
. "$PSScriptRoot/p5-common.ps1"
$out = "evidence/ui-port/u1/$Tag"
if (Test-Path $out) { throw 'Select a new evidence tag; existing data must be retained' }
New-Item -ItemType Directory -Force $out | Out-Null
$prefix = "$out/run"
$control = [IO.Path]::GetFullPath("$out/control.json")
$env:RDI_PROTO_CONTROL = $control
$script:id = 0
function Command([string]$Action,[double]$Value=0) {
    $script:id++
    @{ id=$script:id; action=$Action; value=$Value } | ConvertTo-Json -Compress | Set-Content $control
    Start-Sleep -Milliseconds 1500
}
function Beat {
    $text = [regex]::Replace((Get-Content "$prefix-app.log" -Raw), '\x1b\[[0-9;]*m', '')
    $beats = [regex]::Matches($text, 'Runtime probe data=(\{[^\r\n]+\})')
    $beats[$beats.Count-1].Groups[1].Value | ConvertFrom-Json
}
function Sample([string]$Name,[int]$Seconds = 10) {
    Require-Focus $handle
    $p.Refresh(); $cpu0 = $p.TotalProcessorTime.TotalMilliseconds; $b0 = Beat
    Start-Sleep -Seconds $Seconds
    Require-Focus $handle
    $p.Refresh(); $cpu1 = $p.TotalProcessorTime.TotalMilliseconds; $b1 = Beat
    [pscustomobject]@{ phase=$Name; cpu_pct_of_one_core=[math]::Round(($cpu1-$cpu0)/($Seconds*10),1); clock_start=$b0.elapsed; clock_end=$b1.elapsed; active=$b1.active; scene_frames=$b1.scene_frames-$b0.scene_frames; ui_frames=$b1.ui_frames-$b0.ui_frames; ws_mib=[math]::Round($p.WorkingSet64/1048576,1); private_mib=[math]::Round($p.PrivateMemorySize64/1048576,1) }
}
$p = Start-Process ./target/release/rdi-slint-proto.exe -PassThru -ArgumentList @('--ui-proof-live-scene','--scene-memory-probe','--scene-probe-hold-ms=0','--scene-steve-only') -RedirectStandardOutput "$prefix-app.log" -RedirectStandardError "$prefix-app.err"
$handle = [IntPtr]::Zero
$rows = @()
try {
    $deadline = (Get-Date).AddSeconds(60)
    do {
        $p.Refresh(); if ($p.HasExited) { throw 'Exited during startup' }
        $handle = [P5Window]::Find($p.Id)
        if ($handle -ne [IntPtr]::Zero) { Require-Focus $handle }
        Start-Sleep -Milliseconds 500
        if ((Get-Date) -gt $deadline) { throw 'Startup timed out' }
        $log = Get-Content "$prefix-app.log" -Raw
    } until (($log -match 'First live scene frame') -and ($log -match 'U1 images ready'))
    Start-Sleep -Seconds 5
    $rows += Sample 'live'
    Save-Window $handle "$out/1-live.png"
    Command 'scene-freeze' 1
    Save-Window $handle "$out/2-held-a.png"
    $rows += Sample 'held'
    Save-Window $handle "$out/3-held-b.png"
    Start-Sleep -Seconds 5
    # A held frame must survive a window size change without the scene drawing.
    [P5Window]::SetWindowPos($handle,[IntPtr]::Zero,60,60,1600,1000,4) | Out-Null
    Start-Sleep -Seconds 2
    Save-Window $handle "$out/4-held-resized.png"
    $rows += Sample 'held-resized' 5
    Command 'scene-freeze' 0
    $rows += Sample 'resumed'
    Save-Window $handle "$out/5-resumed.png"
    $rows | ConvertTo-Json | Set-Content "$out/phases.json"
    $rows | Format-Table -AutoSize | Out-String -Width 160
    [P5Window]::PostMessage($handle,0x10,[IntPtr]::Zero,[IntPtr]::Zero) | Out-Null
    if (!$p.WaitForExit(10000)) { throw 'Close timed out' }
    "exit $($p.ExitCode)"
} finally {
    if (!$p.HasExited) { Stop-Prototype $p $handle }
    Remove-Item Env:RDI_PROTO_CONTROL -ErrorAction SilentlyContinue
}
