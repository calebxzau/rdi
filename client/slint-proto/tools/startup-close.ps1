param([int]$Rounds = 4, [string]$OutName = 'close')
. "$PSScriptRoot/p5-common.ps1"
Require-Credentials
$dir = "evidence/bg-port/startup/$OutName"
New-Item -ItemType Directory -Force $dir | Out-Null
$final = './evidence/bg-port/startup/controls/rdi-slint-proto.exe'
$baseline = './evidence/bg-port/startup/pre-change/baseline-p4b.exe'
$cases = @(
    @{ name = 'baseline'; exe = $baseline; compiler = $null; extra = @() },
    @{ name = 'manual-dxc'; exe = $final; compiler = $null; extra = @() },
    @{ name = 'manual-fxc'; exe = $final; compiler = 'fxc'; extra = @() },
    @{ name = 'auto-dxc'; exe = $final; compiler = 'dxc'; extra = @('--gpu-setup=automatic') },
    @{ name = 'auto-fxc'; exe = $final; compiler = 'fxc'; extra = @('--gpu-setup=automatic') }
)
foreach ($run in 1..$Rounds) {
    foreach ($case in $cases) {
        if ($null -eq $case.compiler) { Remove-Item Env:WGPU_DX12_COMPILER -ErrorAction SilentlyContinue } else { $env:WGPU_DX12_COMPILER = $case.compiler }
        $log = "$dir/$($case.name)-$run.log"
        [string[]]$flags = @('--scene-memory-probe', '--scene-probe-unfocused', '--scene-probe-hold-ms=0') + $case.extra
        $p = Start-Process $case.exe -ArgumentList $flags -PassThru -RedirectStandardOutput $log -RedirectStandardError "$log.err"
        Start-Sleep -Seconds 12
        $handle = [P5Window]::Find($p.Id)
        $watch = [Diagnostics.Stopwatch]::StartNew()
        [P5Window]::PostMessage($handle, 0x10, [IntPtr]::Zero, [IntPtr]::Zero) | Out-Null
        $done = $p.WaitForExit(20000)
        $ms = $watch.ElapsedMilliseconds
        if (!$done) { Stop-Process -Id $p.Id -Force; $p.WaitForExit() }
        "$($case.name) run=$run exit_ms=$ms exited=$done code=$($p.ExitCode)"
    }
}
