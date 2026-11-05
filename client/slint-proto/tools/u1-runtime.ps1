param([int]$Rounds = 3, [string]$Tag = 'focused')
. "$PSScriptRoot/p5-common.ps1"
$out = "evidence/ui-port/u1/$Tag"
if (Test-Path $out) { throw 'Select a new evidence tag; existing data must be retained' }
New-Item -ItemType Directory -Force $out | Out-Null
$prefix = "$out/run"
$control = [IO.Path]::GetFullPath("$out/control.json")
$env:RDI_PROTO_CONTROL = $control
$id = 0
function Command([string]$Action,[double]$Value=0) {
    $script:id++
    @{ id=$script:id; action=$Action; value=$Value } | ConvertTo-Json -Compress | Set-Content $control
    Start-Sleep -Milliseconds 1200
}
$p = Start-Process ./target/release/rdi-slint-proto.exe -PassThru -ArgumentList @('--ui-proof','--scene-memory-probe','--scene-probe-hold-ms=0','--scene-steve-only') -RedirectStandardOutput "$prefix-app.log" -RedirectStandardError "$prefix-app.err"
$handle = [IntPtr]::Zero
try {
    $deadline = (Get-Date).AddSeconds(60)
    do {
        $p.Refresh()
        if ($p.HasExited) { throw 'Application exited during startup' }
        $handle = [P5Window]::Find($p.Id)
        if ($handle -ne [IntPtr]::Zero) { Require-Focus $handle }
        Start-Sleep -Milliseconds 500
        if ((Get-Date) -gt $deadline) { throw 'Live scene startup timed out' }
    } until (((Get-Content "$prefix-app.log" -Raw) -match 'First live scene frame') -and ((Get-Content "$prefix-app.log" -Raw) -match 'U1 images ready'))
    # Native foreground and application focus must both be true before measurement.
    Require-Focus $handle
    Save-Window $handle "$out/start.png"
    $start = Get-Date
    foreach ($round in 1..$Rounds) {
        Command 'proof-start'
        foreach ($second in 1..42) {
            Require-Focus $handle
            $p.Refresh()
            @{round=$round;second=$second;focused=([P5Window]::GetForegroundWindow() -eq $handle);ws=$p.WorkingSet64;private=$p.PrivateMemorySize64} |
                ConvertTo-Json -Compress | Add-Content "$out/memory.jsonl"
            Start-Sleep -Seconds 1
        }
    }
    # Native DPI messages exercise resize/cache paths. This is not a physical monitor transition.
    foreach ($dpi in 96,120,192) {
        [P5Window]::Dpi($handle,$dpi)
        Start-Sleep -Seconds 2
        Require-Focus $handle
        foreach ($progress in 0.25,0.5,0.75,1) {
            Command 'proof-freeze' $progress
            Save-Window $handle "$out/panel-$dpi-$progress.png"
        }
        Command 'proof-modal' 1
        Save-Window $handle "$out/modal-$dpi.png"
        Command 'proof-modal' 0
    }
    Command 'proof-freeze' -1
    [P5Window]::ShowWindow($handle,6) | Out-Null
    Start-Sleep -Seconds 2
    $minimized = [P5Window]::IsIconic($handle)
    [P5Window]::ShowWindow($handle,9) | Out-Null
    Require-Focus $handle
    [P5Window]::ShowWindow($handle,3) | Out-Null
    Start-Sleep -Seconds 2
    Save-Window $handle "$out/maximized.png"
    [P5Window]::ShowWindow($handle,9) | Out-Null
    [P5Window]::SetWindowPos($handle,[IntPtr]::Zero,40,40,1600,1000,4) | Out-Null
    Start-Sleep -Seconds 2
    Save-Window $handle "$out/resized.png"
    @{minimized=$minimized;foreground=([P5Window]::GetForegroundWindow() -eq $handle)} | ConvertTo-Json > "$out/window.json"
    [P5Window]::PostMessage($handle,0x10,[IntPtr]::Zero,[IntPtr]::Zero) | Out-Null
    if (!$p.WaitForExit(10000)) { throw 'Close timed out' }
    Set-Content "$out/exit.code" $p.ExitCode
} finally {
    if (!$p.HasExited) { Stop-Prototype $p $handle }
    Remove-Item Env:RDI_PROTO_CONTROL -ErrorAction SilentlyContinue
}
