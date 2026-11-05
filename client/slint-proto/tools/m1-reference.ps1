param([ValidateSet('before','after')][string]$Phase = 'before')
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$out = [IO.Path]::GetFullPath("evidence/m1/$Phase")
New-Item -ItemType Directory -Force $out | Out-Null
function Cargo-Step([string[]]$Arguments, [string]$Log) {
    & cargo @Arguments *> "$out/$Log"
    if ($LASTEXITCODE -ne 0) { throw "cargo failed: $Arguments (see $out/$Log)" }
}
Cargo-Step @('build','--release','--workspace','--examples','--bins') 'build.log'
Cargo-Step @('test','--release','--workspace') 'tests.log'
Copy-Item target/release/rdi-slint-proto.exe "$out/app.exe"
Copy-Item target/release/dxcompiler.dll "$out/dxcompiler.dll"
$package = if ($Phase -eq 'before') { 'rdi-slint-proto' } else { 'rdi-renderer' }
foreach ($round in 1..2) {
    foreach ($compiler in 'fxc','dxc') {
        $env:WGPU_DX12_COMPILER = $compiler
        & target/release/examples/scene_preview.exe "$out/scene-$compiler-$round" 0 15 45 --crowd=50 --capture-only *> "$out/scene-$compiler-$round.log"
        if ($LASTEXITCODE -ne 0) { throw 'preview failed' }
        foreach ($scale in '1','1.25','2') {
            $env:GLASS_CAPTURE_SCALE = $scale
            $env:GLASS_CAPTURE_PATH = "$out/glass-$compiler-$scale-$round.png"
            Cargo-Step @('test','--release','-p',$package,'glass::tests::capture_fixed_frame','--','--ignored','--exact') "glass-$compiler-$scale-$round.log"
        }
    }
}
Remove-Item Env:GLASS_CAPTURE_PATH,Env:GLASS_CAPTURE_SCALE,Env:WGPU_DX12_COMPILER
foreach ($round in 1..6) {
    $p = Start-Process "$out/app.exe" -PassThru -ArgumentList @('--scene-memory-probe','--scene-probe-unfocused','--scene-probe-hold-ms=0','--scene-steve-only') -RedirectStandardOutput "$out/startup-$round.log" -RedirectStandardError "$out/startup-$round.err"
    Start-Sleep -Seconds 6
    $p.Refresh()
    $p | Select-Object WorkingSet64,PrivateMemorySize64 | ConvertTo-Json > "$out/memory-$round.json"
    $timer = [Diagnostics.Stopwatch]::StartNew()
    $closed = $p.CloseMainWindow()
    if (-not $p.WaitForExit(10000)) { throw 'close timed out' }
    "close_ms=$($timer.ElapsedMilliseconds) exit=$($p.ExitCode) requested=$closed" > "$out/close-$round.txt"
}
Set-Content "$out/complete.exit" 0
