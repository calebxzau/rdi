param([int]$Rounds = 6)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$root = (Resolve-Path 'evidence/bg-port/startup').Path
$out = Join-Path $root 'final'
New-Item -ItemType Directory -Force $out | Out-Null
$dllDir = (Resolve-Path 'third_party/dxc/x64').Path
$exe = (Resolve-Path 'target/release/rdi-slint-proto.exe').Path
$basePath = $env:PATH

function Set-Compiler($compiler) {
    if ($null -eq $compiler) {
        Remove-Item Env:WGPU_DX12_COMPILER -ErrorAction SilentlyContinue
        $env:PATH = $basePath
        return
    }
    $env:WGPU_DX12_COMPILER = $compiler
    $env:PATH = if ($compiler -eq 'dxc') { "$dllDir;$basePath" } else { $basePath }
}

# Fixed haze against the full shader, per compiler and scale.
& cargo test --release --bin rdi-slint-proto --no-run *> (Join-Path $out 'capture-build.log')
if ($LASTEXITCODE -ne 0) { throw 'capture test build failed' }
$testExe = Get-ChildItem 'target/release/deps/rdi_slint_proto-*.exe' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
foreach ($scale in '1', '1.25', '2') {
    foreach ($compiler in 'fxc', 'dxc') {
        foreach ($haze in 'generic', 'fixed') {
            Set-Compiler $compiler
            $env:GLASS_CAPTURE_SCALE = $scale
            if ($haze -eq 'generic') { $env:GLASS_CAPTURE_GENERIC_HAZE = '1' } else { Remove-Item Env:GLASS_CAPTURE_GENERIC_HAZE -ErrorAction SilentlyContinue }
            $name = "glass-$compiler-$haze-$scale"
            $env:GLASS_CAPTURE_PATH = Join-Path $out "$name.png"
            & $testExe.FullName --ignored --exact glass::tests::capture_fixed_frame *> (Join-Path $out "$name.log")
            "$name exit=$LASTEXITCODE"
        }
    }
}
Remove-Item Env:GLASS_CAPTURE_GENERIC_HAZE, Env:GLASS_CAPTURE_SCALE, Env:GLASS_CAPTURE_PATH -ErrorAction SilentlyContinue

function Measure-App($name, $program, $compiler, [string[]]$extra, [int]$seconds) {
    Set-Compiler $compiler
    $log = Join-Path $out "$name.log"
    [string[]]$arguments = @('--log', 'info', '--scene-memory-probe', '--scene-probe-unfocused', '--scene-probe-hold-ms=0') + $extra
    $p = Start-Process -FilePath $program -PassThru -ArgumentList $arguments -RedirectStandardOutput $log -RedirectStandardError "$log.err"
    Start-Sleep -Seconds $seconds
    $p.Refresh()
    $exited = $p.HasExited
    if (-not $exited) {
        $names = $p.Modules | ForEach-Object { $_.ModuleName }
        Stop-Process -Id $p.Id -Force
        $p.WaitForExit()
    }
    Start-Sleep -Seconds 1
    "$name exited=$exited dxc=$($names -contains 'dxcompiler.dll') fxc=$($names -contains 'd3dcompiler_47.dll')"
}

$fallback = Join-Path $out 'fallback'
foreach ($case in 'missing', 'corrupt') {
    $dir = Join-Path $fallback $case
    New-Item -ItemType Directory -Force $dir | Out-Null
    Copy-Item $exe $dir -Force
    if ($case -eq 'corrupt') { Set-Content (Join-Path $dir 'dxcompiler.dll') 'not a dll' }
    Measure-App "fallback-$case" (Join-Path $dir 'rdi-slint-proto.exe') $null @() 8
}

$cases = @(
    @{ name = 'manual-dxc'; compiler = $null; extra = @() },
    @{ name = 'manual-fxc'; compiler = 'fxc'; extra = @() },
    @{ name = 'auto-fxc'; compiler = 'fxc'; extra = @('--gpu-setup=automatic') }
)
foreach ($run in 1..$Rounds) {
    foreach ($case in $cases) {
        Measure-App "app-$($case.name)-$run" $exe $case.compiler $case.extra 8
    }
}
Set-Compiler $null
Set-Content (Join-Path $out 'complete.exit') 0
