$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$out = [IO.Path]::GetFullPath('evidence/m1/runtime')
New-Item -ItemType Directory -Force $out | Out-Null
$credentials = Get-Content '\\wsl.localhost\Debian\home\calebxzhou\.config\rdi\.test_usr_pwd'
if ($credentials.Count -lt 2) { throw 'Test account file must have two lines' }
$env:RDI_PROTO_USR = $credentials[0]
$env:RDI_PROTO_PWD = $credentials[1]
$credentials = $null
function Measure-App($program, $name) {
    $p = Start-Process $program -PassThru -ArgumentList @('--scene-memory-probe','--scene-probe-unfocused','--scene-probe-hold-ms=0','--scene-steve-only') -RedirectStandardOutput "$out/$name.log" -RedirectStandardError "$out/$name.err"
    Start-Sleep -Seconds 7
    $p.Refresh()
    if ($p.HasExited) { throw "$name exited early: $($p.ExitCode)" }
    $p | Select-Object WorkingSet64,PrivateMemorySize64 | ConvertTo-Json > "$out/$name-memory.json"
    $timer = [Diagnostics.Stopwatch]::StartNew()
    $closed = $p.CloseMainWindow()
    if (-not $p.WaitForExit(10000)) { throw "$name close timed out" }
    "close_ms=$($timer.ElapsedMilliseconds) exit=$($p.ExitCode) requested=$closed" > "$out/$name-close.txt"
}
try {
    foreach ($round in 1..6) {
        $phases = if ($round % 2) { @('before','after') } else { @('after','before') }
        foreach ($phase in $phases) {
            Measure-App ([IO.Path]::GetFullPath("evidence/m1/$phase/app.exe")) "$phase-$round"
        }
    }
    foreach ($case in 'missing','unloadable') {
        $dir = "$out/$case"
        New-Item -ItemType Directory -Force $dir | Out-Null
        Copy-Item evidence/m1/after/app.exe "$dir/app.exe"
        if ($case -eq 'unloadable') { Set-Content "$dir/dxcompiler.dll" 'not a DLL' }
        Measure-App "$dir/app.exe" "fallback-$case"
    }
    Set-Content "$out/complete.exit" 0
} finally {
    Remove-Item Env:RDI_PROTO_USR,Env:RDI_PROTO_PWD -ErrorAction SilentlyContinue
}
