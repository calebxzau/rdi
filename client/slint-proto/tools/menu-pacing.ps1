# Idle menu pacing: time between rendered frames and process CPU, per scene pacer and display.
# Signs in with the test account in a temporary data directory, which is removed afterwards.
# The window takes the foreground for the whole run, so do not use the machine during it.
param([int]$Rounds = 2, [int[]]$PacerMs = @(16, 4), [int]$Seconds = 30, [switch]$PrimaryOnly)
. "$PSScriptRoot/p5-common.ps1"
Add-Type -AssemblyName System.Windows.Forms
Add-Type -TypeDefinition @"
using System;using System.Runtime.InteropServices;
public static class PacingDisplay{
[StructLayout(LayoutKind.Sequential,CharSet=CharSet.Unicode)] public struct DEVMODE{
[MarshalAs(UnmanagedType.ByValTStr,SizeConst=32)]public string dmDeviceName;public ushort specVersion,driverVersion,dmSize,dmDriverExtra;public uint dmFields;public int posX,posY;public uint orient,fixedOut;public short color,duplex,yRes,ttOpt,collate;
[MarshalAs(UnmanagedType.ByValTStr,SizeConst=32)]public string dmFormName;public ushort logPixels;public uint bpp,width,height,flags,frequency,i1,i2,i3,i4,r1,r2,p1,p2;}
[DllImport("user32.dll",CharSet=CharSet.Unicode)]public static extern bool EnumDisplaySettings(string n,int m,ref DEVMODE d);
public static string Mode(string n){var d=new DEVMODE();d.dmSize=(ushort)Marshal.SizeOf(d);EnumDisplaySettings(n,-1,ref d);return d.width+"x"+d.height+"@"+d.frequency;}
}
"@
if (Get-Process rdi-slint-proto -ErrorAction SilentlyContinue) { throw 'Close the running launcher first; it would affect the numbers' }
$screens = @([Windows.Forms.Screen]::AllScreens | Sort-Object { !$_.Primary })
if ($PrimaryOnly) { $screens = @($screens[0]) }
$out = 'evidence/frame-pacing/' + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
New-Item -ItemType Directory -Force $out | Out-Null
$data = Join-Path ([IO.Path]::GetTempPath()) ("rdi-pacing-" + [Guid]::NewGuid())
$java = (Get-Command java).Source
$libs = (Resolve-Path '../ui/ui-service/build/install/ui-service/lib').Path + '/*'
$credentials = [IO.File]::ReadAllLines('\\wsl.localhost\Debian\home\calebxzhou\.config\rdi\.test_usr_pwd')
$env:RDI_PROTO_USR = $credentials[0]; $env:RDI_PROTO_PWD = $credentials[1]; $credentials = $null
$results = [Collections.Generic.List[object]]::new()
try {
    foreach ($round in 1..$Rounds) {
        foreach ($screen in $screens) {
            foreach ($pacer in $PacerMs) {
                $mode = [PacingDisplay]::Mode($screen.DeviceName)
                $name = "$round-$($screen.DeviceName -replace '\W','')-$pacer"
                Write-Output "pacing: $name $mode"
                $run = Join-Path $data $name
                New-Item -ItemType Directory -Force $run | Out-Null
                $env:RDI_DATA_DIR = $run
                $env:RDI_SERVICE_COMMAND = ConvertTo-Json -Compress -InputObject @($java, "-Duser.dir=$run", '-Djava.awt.headless=true', '-cp', $libs, 'calebxzau.rdi.service.MainKt')
                $log = "$out/$name.log"
                $p = Start-Process ./target/release/rdi-slint-proto.exe -PassThru -ArgumentList @('--frame-stats', "--scene-pacer-ms=$pacer") -RedirectStandardOutput $log -RedirectStandardError "$log.err"
                Start-Sleep -Seconds 5
                $h = [P5Window]::Find($p.Id)
                try {
                    $until = (Get-Date).AddSeconds(45)
                    while ((Get-Date) -lt $until -and (Get-Content $log -Raw) -notmatch 'Menu data loaded') { Start-Sleep -Milliseconds 500 }
                    # NOSIZE|NOZORDER|NOACTIVATE: the window stays its size on the chosen display.
                    [P5Window]::SetWindowPos($h, [IntPtr]::Zero, $screen.Bounds.X + 40, $screen.Bounds.Y + 40, 0, 0, 0x15) | Out-Null
                    Require-Focus $h
                    # The launcher cannot report focus without the probe, so make sure it really has it.
                    [P5Window]::Reacquire($h); Start-Sleep -Milliseconds 300; Require-Focus $h
                    Start-Sleep -Seconds 3
                    if ([Windows.Forms.Screen]::FromHandle($h).DeviceName -ne $screen.DeviceName) { throw "Window is not on $($screen.DeviceName)" }
                    $p.Refresh(); $cpu = $p.CPU; $started = Get-Date
                    Start-Sleep -Seconds $Seconds
                    Require-Focus $h
                    $p.Refresh(); $cores = ($p.CPU - $cpu) / ((Get-Date) - $started).TotalSeconds
                } finally { Stop-Prototype $p $h }
                $text = [regex]::Replace((Get-Content $log -Raw), '\x1b\[[0-9;]*m', '')
                $lines = @([regex]::Matches($text, 'count=(\d+).*?over_25ms=(\d+) over_30ms=(\d+) Frame pacing|Frame pacing count=(\d+).*?over_25ms=(\d+) over_30ms=(\d+)'))
                if (!$lines.Count) { throw "$name reported no frames: the scene never ran, usually because the window lacked focus" }
                $sum = { param($a, $b) ($lines | ForEach-Object { [int]($_.Groups[$a].Value + $_.Groups[$b].Value) } | Measure-Object -Sum).Sum }
                $results.Add([pscustomobject]@{Round = $round; Display = $mode; PacerMs = $pacer; Frames = (& $sum 1 4); Over25 = (& $sum 2 5); Over30 = (& $sum 3 6); CpuCores = [math]::Round($cores, 2) })
                Start-Sleep -Seconds 2
            }
        }
    }
} finally {
    Remove-Item Env:RDI_PROTO_USR, Env:RDI_PROTO_PWD, Env:RDI_DATA_DIR, Env:RDI_SERVICE_COMMAND -ErrorAction SilentlyContinue
    # Holds a saved test password, so it must not outlive the run.
    if (Test-Path $data) { Remove-Item $data -Recurse -Force }
}
$results | ConvertTo-Json | Set-Content "$out/results.json"
$results | Format-Table -AutoSize | Out-String
Write-Output "evidence: $out"
