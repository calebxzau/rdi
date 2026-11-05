param([int]$Rounds = 6, [int]$PreviewRounds = 3)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$root = (Resolve-Path 'evidence/bg-port/startup').Path
$out = Join-Path $root 'ab'
New-Item -ItemType Directory -Force $out | Out-Null
$dllDir = (Resolve-Path 'third_party/dxc/x64').Path
$exe = (Resolve-Path 'target/release/rdi-slint-proto.exe').Path
$preview = (Resolve-Path 'target/release/examples/scene_preview.exe').Path
$basePath = $env:PATH

function Set-Compiler($compiler) {
    $env:WGPU_DX12_COMPILER = $compiler
    $env:PATH = if ($compiler -eq 'dxc') { "$dllDir;$basePath" } else { $basePath }
}

# Glass pixels with each compiler; fxc twice to see whether the capture itself is repeatable.
& cargo test --release --bin rdi-slint-proto --no-run *> (Join-Path $out 'capture-build.log')
if ($LASTEXITCODE -ne 0) { throw 'capture test build failed' }
$testExe = Get-ChildItem 'target/release/deps/rdi_slint_proto-*.exe' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
foreach ($case in 'fxc', 'fxc2', 'dxc') {
    Set-Compiler ($case -replace '\d', '')
    $env:GLASS_CAPTURE_PATH = Join-Path $out "glass-$case.png"
    & $testExe.FullName --ignored --exact glass::tests::capture_fixed_frame *> (Join-Path $out "glass-$case.log")
    "glass $case exit=$LASTEXITCODE"
}
Remove-Item Env:GLASS_CAPTURE_PATH

# Which render stage carries the headless frame time difference.
$variants = [ordered]@{
    full = @(); 'p1' = @('--scene-p1-only'); 'p2' = @('--scene-p2-only'); 'p3' = @('--scene-p3-only')
    'skip-fog' = @('--scene-skip-post=fog'); 'skip-copy' = @('--scene-skip-post=copy'); 'skip-mip' = @('--scene-skip-post=mip')
    'skip-bloom' = @('--scene-skip-post=bloom'); 'skip-final' = @('--scene-skip-post=final')
}
foreach ($run in 1..$PreviewRounds) {
    foreach ($variant in $variants.Keys) {
        foreach ($compiler in 'fxc', 'dxc') {
            Set-Compiler $compiler
            $name = "preview-$variant-$compiler-$run"
            [string[]]$arguments = @((Join-Path $out $name), '0') + $variants[$variant]
            & $preview @arguments *> (Join-Path $out "$name.log")
            "$name exit=$LASTEXITCODE"
        }
    }
}

function Measure-App($name, $program, $compiler, [string[]]$extra, [int]$seconds) {
    if ($null -ne $compiler) { Set-Compiler $compiler } else { Remove-Item Env:WGPU_DX12_COMPILER -ErrorAction SilentlyContinue; $env:PATH = $basePath }
    $log = Join-Path $out "$name.log"
    [string[]]$arguments = @('--log', 'info', '--scene-memory-probe', '--scene-probe-unfocused', '--scene-probe-hold-ms=0') + $extra
    $p = Start-Process -FilePath $program -PassThru -ArgumentList $arguments -RedirectStandardOutput $log -RedirectStandardError "$log.err"
    Start-Sleep -Seconds ($seconds - 10)
    $p.Refresh()
    if ($p.HasExited) { return [pscustomobject]@{ name = $name; exited = $true } }
    $cpu0 = $p.TotalProcessorTime.TotalMilliseconds
    $at0 = [Diagnostics.Stopwatch]::StartNew()
    $busy = @()
    while ($at0.Elapsed.TotalSeconds -lt 10) {
        Start-Sleep -Seconds 1
        $sum = (Get-CimInstance Win32_PerfFormattedData_GPUPerformanceCounters_GPUEngine -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -like "pid_$($p.Id)_*engtype_3D" } | Measure-Object UtilizationPercentage -Sum).Sum
        $busy += [double]$sum
    }
    $p.Refresh()
    $cpuPercent = ($p.TotalProcessorTime.TotalMilliseconds - $cpu0) / $at0.Elapsed.TotalMilliseconds * 100
    $names = $p.Modules | ForEach-Object { $_.ModuleName }
    $result = [pscustomobject]@{
        name = $name; exited = $false
        ws = [math]::Round($p.WorkingSet64 / 1MB, 1); private = [math]::Round($p.PrivateMemorySize64 / 1MB, 1)
        busy3d = [math]::Round(($busy | Measure-Object -Average).Average, 2); cpu = [math]::Round($cpuPercent, 2)
        dxc = $names -contains 'dxcompiler.dll'; fxc = $names -contains 'd3dcompiler_47.dll'
    }
    Stop-Process -Id $p.Id -Force
    $p.WaitForExit()
    Start-Sleep -Seconds 1
    return $result
}

# Fallback: the same exe beside no DLL, and beside a DLL that cannot load.
$fallback = Join-Path $out 'fallback'
foreach ($case in 'missing', 'corrupt') {
    $dir = Join-Path $fallback $case
    New-Item -ItemType Directory -Force $dir | Out-Null
    Copy-Item $exe $dir -Force
    if ($case -eq 'corrupt') { Set-Content (Join-Path $dir 'dxcompiler.dll') 'not a dll' }
    Measure-App "fallback-$case" (Join-Path $dir 'rdi-slint-proto.exe') $null @() 12 | ConvertTo-Json -Compress
}

$summary = Join-Path $out 'app-summary.jsonl'
Set-Content $summary ''
$cases = @(
    @{ name = 'manual-dxc'; compiler = $null; extra = @() },
    @{ name = 'manual-fxc'; compiler = 'fxc'; extra = @() },
    @{ name = 'auto-dxc'; compiler = 'dxc'; extra = @('--gpu-setup=automatic') },
    @{ name = 'auto-fxc'; compiler = 'fxc'; extra = @('--gpu-setup=automatic') }
)
foreach ($run in 1..$Rounds) {
    foreach ($case in $cases) {
        $line = Measure-App "app-$($case.name)-$run" $exe $case.compiler $case.extra 20 | ConvertTo-Json -Compress
        $line
        Add-Content $summary $line
    }
}
Set-Content (Join-Path $out 'complete.exit') 0
