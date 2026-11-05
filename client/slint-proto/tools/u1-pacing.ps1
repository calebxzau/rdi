# GU1 rerun: list and panel pacing at 60Hz with and without the scene and glass.
# Runs on the primary display and refuses to measure anywhere else. It never changes the display mode.
param([string]$Tag = 'pacing-60hz', [int]$Rounds = 3, [int]$RefreshHz = 60, [string[]]$Cases = @('held','menu'))
. "$PSScriptRoot/p5-common.ps1"
Add-Type -AssemblyName System.Windows.Forms
$primary = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
Add-Type -TypeDefinition @"
using System;using System.Runtime.InteropServices;
public static class U1Display{
[StructLayout(LayoutKind.Sequential,CharSet=CharSet.Unicode)] public struct DEVMODE{
[MarshalAs(UnmanagedType.ByValTStr,SizeConst=32)]public string dmDeviceName;public ushort specVersion,driverVersion,dmSize,dmDriverExtra;public uint dmFields;public int posX,posY;public uint orientation,fixedOutput;public short color,duplex,yRes,tt,collate;
[MarshalAs(UnmanagedType.ByValTStr,SizeConst=32)]public string dmFormName;public ushort logPixels;public uint bpp,width,height,flags,frequency,i1,i2,i3,i4,r1,r2,p1,p2;}
[DllImport("user32.dll",CharSet=CharSet.Unicode)]public static extern bool EnumDisplaySettings(string n,int m,ref DEVMODE d);
public static uint Hz(){var d=new DEVMODE();d.dmSize=(ushort)Marshal.SizeOf(d);EnumDisplaySettings(null,-1,ref d);return d.frequency;}
}
"@
# A disconnected or locked session has no foreground window, so no run could ever be valid.
if ([P5Window]::GetForegroundWindow() -eq [IntPtr]::Zero) { throw 'No foreground window: the Windows session is disconnected or locked' }
if ([U1Display]::Hz() -ne $RefreshHz) { throw "Primary display reports $([U1Display]::Hz())Hz, expected ${RefreshHz}Hz" }
"Primary display $primary at $([U1Display]::Hz())Hz"
if (Get-Process rdi-slint-proto -ErrorAction SilentlyContinue) { throw 'Another prototype is running; close it so it cannot affect the numbers' }
$root = "evidence/ui-port/u1/$Tag"
if (Test-Path $root) { throw 'Select a new evidence tag; existing data must be retained' }
New-Item -ItemType Directory -Force $root | Out-Null
# Interleaved so slow drift (heat, background work) does not land on a single case.
$list = @('--ui-proof','--scene-memory-probe','--scene-probe-hold-ms=0','--scene-steve-only')
$argumentSets = @{
    held = $list
    live = $list + '--ui-proof-live-scene'
    static = $list + '--no-scene'
    slint = $list + '--no-glass'
    menu = @('--menu-proof','--show-others','--all-playable','--scene-memory-probe','--scene-probe-hold-ms=0','--scene-steve-only')
}
$order = foreach ($round in 1..$Rounds) { if ($round % 2) { $Cases } else { [array]::Reverse($Cases); $Cases; [array]::Reverse($Cases) } }
$credentials = Get-Content '\\wsl.localhost\Debian\home\calebxzhou\.config\rdi\.test_usr_pwd'
if ($credentials.Count -lt 2) { throw 'Expected two credential lines' }
$env:RDI_PROTO_USR = $credentials[0]; $env:RDI_PROTO_PWD = $credentials[1]; $credentials = $null
$index = 0
    foreach ($case in $order) {
        $index++
        $out = "$root/$index-$case"
        New-Item -ItemType Directory -Force $out | Out-Null
        $prefix = "$out/run"
        $control = [IO.Path]::GetFullPath("$out/control.json")
        $env:RDI_PROTO_CONTROL = $control
        $arguments = $argumentSets[$case]
        $p = Start-Process ./target/release/rdi-slint-proto.exe -PassThru -ArgumentList $arguments -RedirectStandardOutput "$prefix-app.log" -RedirectStandardError "$prefix-app.err"
        $handle = [IntPtr]::Zero
        try {
            $deadline = (Get-Date).AddSeconds(60)
            do {
                $p.Refresh()
                if ($p.HasExited) { throw "$case exited during startup" }
                $handle = [P5Window]::Find($p.Id)
                if ($handle -ne [IntPtr]::Zero) { Require-Focus $handle }
                Start-Sleep -Milliseconds 500
                if ((Get-Date) -gt $deadline) { throw "$case startup timed out" }
                $log = Get-Content "$prefix-app.log" -Raw
                $ready = if ($case -eq 'menu') { $log -match 'Menu proof ready' } else { $log -match 'U1 images ready' }
            } until ($ready -and ($log -match 'First live scene frame' -or $case -in 'static','slint'))
            Start-Sleep -Seconds 5
            Require-Focus $handle
            $r = New-Object P5Window+RECT; [P5Window]::GetWindowRect($handle,[ref]$r) | Out-Null
            @{ left=$r.L; top=$r.T; right=$r.R; bottom=$r.B; primary="$primary" } | ConvertTo-Json -Compress | Set-Content "$out/window.json"
            if ($r.L -lt $primary.Left -or $r.T -lt $primary.Top -or $r.R -gt $primary.Right -or $r.B -gt $primary.Bottom) { throw "$case window is not fully on the primary display" }
            @{ id = 1; action = 'proof-start'; value = 0 } | ConvertTo-Json -Compress | Set-Content $control
            foreach ($second in 1..43) {
                Require-Focus $handle
                $p.Refresh()
                @{ second=$second; focused=([P5Window]::GetForegroundWindow() -eq $handle); ws=$p.WorkingSet64; private=$p.PrivateMemorySize64 } | ConvertTo-Json -Compress | Add-Content "$out/memory.jsonl"
                Start-Sleep -Seconds 1
            }
            # The run ends on the app's clock, which starts when it polls the control file.
            $deadline = (Get-Date).AddSeconds(20)
            while ((Get-Content "$prefix-app.log" -Raw) -notmatch 'U1 measurements') {
                if ((Get-Date) -gt $deadline) { throw "$case produced no measurements" }
                Require-Focus $handle
                Start-Sleep -Seconds 1
            }
            [P5Window]::PostMessage($handle,0x10,[IntPtr]::Zero,[IntPtr]::Zero) | Out-Null
            if (!$p.WaitForExit(10000)) { throw "$case close timed out" }
            Set-Content "$out/exit.code" $p.ExitCode
        } finally {
            if (!$p.HasExited) { Stop-Prototype $p $handle }
            Remove-Item Env:RDI_PROTO_CONTROL -ErrorAction SilentlyContinue
        }
    }
