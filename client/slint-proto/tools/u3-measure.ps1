param([string]$Tag = 'release-measure', [ValidateRange(1,3)][int]$Runs=3, [switch]$NoWarmup)
. "$PSScriptRoot/p5-common.ps1"
$out = "evidence/ui-port/u3/$Tag"
if (Test-Path $out) {throw 'Choose a new tag to preserve evidence'}
New-Item -ItemType Directory -Force $out | Out-Null
Get-FileHash target/release/rdi-slint-proto.exe | Select-Object Algorithm,Hash | ConvertTo-Json | Set-Content "$out/binary.json"
$credentialLines = @(& wsl.exe -e cat /home/calebxzhou/.config/rdi/.test_usr_pwd)
if ($LASTEXITCODE -ne 0 -or $credentialLines.Count -lt 2) { throw 'Cannot read test credentials' }
$results = @()
try {
    $env:RDI_PROTO_USR = $credentialLines[0]
    $env:RDI_PROTO_PWD = $credentialLines[1]
    $credentialLines = $null
    foreach ($run in $(if($NoWarmup){1..$Runs}else{0..$Runs})) {
        $prefix = "$out/run-$run"
        $p = Start-Process ./target/release/rdi-slint-proto.exe -PassThru -ArgumentList @('--show-others','--scale','2','--scene-memory-probe','--scene-probe-hold-ms=0') -RedirectStandardOutput "$prefix-app.log" -RedirectStandardError "$prefix-app.err"
        $handle = [IntPtr]::Zero
        try {
            Start-Sleep -Seconds 5
            $handle = [P5Window]::Find($p.Id)
            Require-Focus $handle
            [P5Window]::SetWindowPos($handle,[IntPtr]::Zero,40,40,2560,1440,4) | Out-Null
            $deadline = (Get-Date).AddSeconds(60)
            do {
                Require-Focus $handle
                $log = Get-Content "$prefix-app.log" -Raw
                if ((Get-Date) -gt $deadline) {throw 'Authenticated menu/scene startup timed out'}
                Start-Sleep -Milliseconds 500
            } until ($log -match 'First live scene frame' -and $log -match 'Menu data loaded')
            Start-Sleep -Seconds 10
            $service = @(Get-CimInstance Win32_Process | Where-Object {$_.ParentProcessId -eq $p.Id -and $_.Name -match 'java'})
            if ($service.Count -ne 1) {throw 'Expected exactly one Kotlin service child'}
            $samples = @()
            foreach ($sample in 1..5) {
                Require-Focus $handle
                $text = [regex]::Replace((Get-Content "$prefix-app.log" -Raw),'\x1b\[[0-9;]*m','')
                $beats = [regex]::Matches($text,'Runtime probe data=(\{[^\r\n]+\})')
                if (!$beats.Count) {throw 'Missing application heartbeat'}
                $beat = $beats[$beats.Count-1].Groups[1].Value | ConvertFrom-Json
                if (!$beat.focused -or !$beat.active -or !$beat.bound -or $beat.minimized -or !$beat.visible) {throw 'Invalid application focus/scene state'}
                $p.Refresh(); $jvm = Get-Process -Id $service[0].ProcessId
                $samples += [pscustomobject]@{uiWSMiB=$p.WorkingSet64/1MB;uiPrivateMiB=$p.PrivateMemorySize64/1MB;serviceWSMiB=$jvm.WorkingSet64/1MB;servicePrivateMiB=$jvm.PrivateMemorySize64/1MB;osFocused=([P5Window]::GetForegroundWindow() -eq $handle);appFocused=$beat.focused;active=$beat.active;elapsed=$beat.elapsed;sceneFrames=$beat.scene_frames}
                Start-Sleep -Seconds 2
            }
            & "$PSScriptRoot/capture.ps1" "ui-port/u3/$Tag/menu-$run.png" | Out-Null
            $results += [pscustomobject]@{run=$run;warmup=($run -eq 0);samples=$samples}
            $results | ConvertTo-Json -Depth 6 | Set-Content "$out/samples.json"
            "Completed menu run $run"
        } finally {Stop-Prototype $p $handle}
    }
} finally {
    Remove-Item Env:RDI_PROTO_USR -ErrorAction SilentlyContinue
    Remove-Item Env:RDI_PROTO_PWD -ErrorAction SilentlyContinue
    $credentialLines = $null
}
# Independent service startup/handshake samples exclude login/network and use the installed service.
foreach ($run in 1..3) {& "$PSScriptRoot/u2-service-smoke.ps1" *> "$out/service-$run.log"}
