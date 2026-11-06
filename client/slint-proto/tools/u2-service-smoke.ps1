param([switch]$LaunchGame, [switch]$DescribeRoom)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path "$PSScriptRoot/../../..").Path
$lib = "$repo/client/ui/ui-service/build/install/ui-service/lib/*"
$java = (Get-Command java).Source
$info = [System.Diagnostics.ProcessStartInfo]::new($java)
$info.ArgumentList.Add('-Djava.awt.headless=true')
$info.ArgumentList.Add('-cp'); $info.ArgumentList.Add($lib)
$info.ArgumentList.Add('calebxzau.rdi.service.MainKt')
$info.WorkingDirectory = "$repo/client/ui/run"
$info.UseShellExecute = $false
$info.RedirectStandardInput = $true
$info.RedirectStandardOutput = $true
$info.RedirectStandardError = $true
$timer = [Diagnostics.Stopwatch]::StartNew()
$process = [Diagnostics.Process]::Start($info)
try {
    function Send-Frame($message) {
        $bytes = [Text.Encoding]::UTF8.GetBytes(($message | ConvertTo-Json -Depth 10 -Compress))
        $header = [BitConverter]::GetBytes([Net.IPAddress]::HostToNetworkOrder([int]$bytes.Length))
        $process.StandardInput.BaseStream.Write($header)
        $process.StandardInput.BaseStream.Write($bytes)
        $process.StandardInput.BaseStream.Flush()
    }
    function Read-Exact($buffer) {
        $offset = 0
        while ($offset -lt $buffer.Length) {
            $read = $process.StandardOutput.BaseStream.ReadAsync($buffer, $offset, $buffer.Length - $offset)
            if (-not $read.Wait(15000)) { throw 'Service response timed out' }
            if ($read.Result -eq 0) { throw 'Service closed stdout' }
            $offset += $read.Result
        }
    }
    function Read-Frame {
        $header = [byte[]]::new(4)
        Read-Exact $header
        $size = [Net.IPAddress]::NetworkToHostOrder([BitConverter]::ToInt32($header))
        if ($size -lt 1 -or $size -gt 8388608) { throw 'Invalid frame' }
        $body = [byte[]]::new($size)
        Read-Exact $body
        [Text.Encoding]::UTF8.GetString($body) | ConvertFrom-Json
    }
    Send-Frame @{id=1;method='handshake';params=@{protocolVersion=1}}
    $reply = Read-Frame
    if ($reply.result.protocolVersion -ne 1) { throw 'Handshake failed' }
    'HandshakeMs=' + $timer.ElapsedMilliseconds
    $process.Refresh()
    'ServiceWorkingSetMiB=' + [math]::Round($process.WorkingSet64 / 1MB, 2)
    Send-Frame @{id=2;method='login';params=@{usr='';pwd=''}}
    $reply = Read-Frame
    if ($reply.error.message -ne '未填写完整') { throw 'Validation mismatch' }
    'EmptyLogin=pass'
    if ($env:RDI_PROTO_USR -and $env:RDI_PROTO_PWD) {
        Send-Frame @{id=3;method='login';params=@{usr=$env:RDI_PROTO_USR;pwd=$env:RDI_PROTO_PWD}}
        $reply = Read-Frame
        if (-not $reply.result.loggedIn) { throw ('Real login failed: ' + $reply.error.message) }
        'RealLogin=pass'
        Send-Frame @{id=4;method='hostList.subscribe';params=@{}}
        $reply = Read-Frame
        $state = $null
        $acknowledged = $false
        while (-not $state -or -not $acknowledged) {
            if ($reply.id -eq 4) { if ($reply.error) { throw 'Host list failed' }; $acknowledged = $true }
            if ($reply.event -eq 'hostList.state' -and -not $reply.params.initialLoading) { $state = $reply }
            if (-not $state -or -not $acknowledged) { $reply = Read-Frame }
        }
        'HostCount=' + @($state.params.hosts).Count
        if ($DescribeRoom) { $state.params.hosts | ForEach-Object { 'Room=' + $_.name + '; Pack=' + $_.modpackName } }
        if ($LaunchGame) {
            $hostEntry = @($state.params.hosts | Where-Object { $_.playable -ne $false }) | Select-Object -First 1
            if (-not $hostEntry) { throw 'No playable test room' }
            Send-Frame @{id=5;method='host.start';params=@{hostId=$hostEntry._id}}
            $reply = Read-Frame
            if ($reply.error) { throw ('Host start failed: ' + $reply.error.message) }
            'HostStartAccepted=pass'
            Send-Frame @{id=6;method='game.subscribe';params=@{}}
            $deadline = [DateTime]::UtcNow.AddSeconds(120)
            while ([DateTime]::UtcNow -lt $deadline) {
                $reply = Read-Frame
                if ($reply.event -eq 'game.state') {
                    $sessions = @($reply.params.sessions)
                    if (@($sessions | Where-Object { $_.running }).Count -gt 0) { 'GameProcessRunning=pass'; Start-Sleep -Seconds 30; break }
                    $failed = @($sessions | Where-Object { -not $_.preparing -and $_.exitMessage })
                    if ($failed.Count -gt 0) { throw ('Game preparation failed: ' + $failed[0].exitMessage) }
                }
            }
        }
    }
    $process.StandardInput.Close()
    if (-not $process.WaitForExit(10000)) { throw 'EOF shutdown timed out' }
    'EofExit=' + $process.ExitCode
} finally {
    if (-not $process.HasExited) { $process.Kill(); $process.WaitForExit() }
    $process.Dispose()
}
