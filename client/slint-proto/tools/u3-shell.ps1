param([string]$Tag = 'shell-freeze')
. "$PSScriptRoot/p5-common.ps1"
$out = "evidence/ui-port/u3/$Tag"
if (Test-Path $out) {throw 'Choose a new tag to preserve evidence'}
New-Item -ItemType Directory -Force $out | Out-Null
Get-FileHash target/release/rdi-slint-proto.exe | Select-Object Algorithm,Hash | ConvertTo-Json | Set-Content "$out/binary.json"
$prefix = "$out/run"
$control = (Join-Path (Resolve-Path $out).Path 'control.json')
$env:RDI_PROTO_CONTROL = $control
$script:commandId = 0
function Command([string]$Action, [double]$Value=0) {
    $script:commandId++
    @{id=$script:commandId;action=$Action;value=$Value}|ConvertTo-Json -Compress|Set-Content $control
    Start-Sleep -Milliseconds 2800
}
function Beat {
    $text=[regex]::Replace((Get-Content "$prefix-app.log" -Raw),'\x1b\[[0-9;]*m','')
    $beats=[regex]::Matches($text,'Runtime probe data=(\{[^\r\n]+\})')
    if (!$beats.Count) {throw 'Missing heartbeat'}
    $beats[$beats.Count-1].Groups[1].Value|ConvertFrom-Json
}
function Sample([string]$Phase,[bool]$Held) {
    Require-Focus $handle
    $deadline = (Get-Date).AddSeconds(10)
    do {
        $ready = Beat
        if ((Get-Date) -gt $deadline) {throw "Scene state did not settle in $Phase"}
        if ($ready.active -eq $Held) {Start-Sleep -Milliseconds 500}
    } while ($ready.active -eq $Held)
    # The first heartbeat that acknowledges a control may precede its next timer tick;
    # allow in-flight scene completion before measuring a steady held interval.
    Start-Sleep -Seconds 1
    $a=Beat
    Start-Sleep -Seconds 3
    $b=Beat
    if (!$a.focused -or !$b.focused -or !$a.bound -or !$b.bound) {throw 'Invalid application focus/scene state'}
    $frames=$b.scene_frames-$a.scene_frames
    if (($Held -and ($frames -ne 0 -or $b.active)) -or (!$Held -and ($frames -le 0 -or !$b.active))) {throw "Unexpected scene activity in $Phase"}
    [pscustomobject]@{phase=$Phase;held=$Held;sceneFrames=$frames;clockStart=$a.elapsed;clockEnd=$b.elapsed;gallery=$b.gallery;gameRunning=$b.game_running;canBack=$b.can_back;focused=$b.focused}
}
$credentials=@(& wsl.exe -e cat /home/calebxzhou/.config/rdi/.test_usr_pwd)
if ($LASTEXITCODE -ne 0 -or $credentials.Count -lt 2) {throw 'Cannot read test credentials'}
try {
    $env:RDI_PROTO_USR=$credentials[0];$env:RDI_PROTO_PWD=$credentials[1];$credentials=$null
    $p=Start-Process ./target/release/rdi-slint-proto.exe -PassThru -ArgumentList @('--gallery','--scale','2','--scene-memory-probe','--scene-probe-hold-ms=0') -RedirectStandardOutput "$prefix-app.log" -RedirectStandardError "$prefix-app.err"
} finally {Remove-Item Env:RDI_PROTO_USR,Env:RDI_PROTO_PWD -ErrorAction SilentlyContinue}
$handle=[IntPtr]::Zero
$rows=@()
try {
    Start-Sleep -Seconds 6
    $handle=[P5Window]::Find($p.Id);Require-Focus $handle
    [P5Window]::SetWindowPos($handle,[IntPtr]::Zero,40,40,2560,1440,4)|Out-Null
    $deadline=(Get-Date).AddSeconds(60)
    do {Require-Focus $handle;Start-Sleep -Seconds 1;if((Get-Date) -gt $deadline){throw 'Scene did not bind'}} until ((Beat).bound)
    $rows+=Sample 'gallery-held' $true
    Command 'shell-home'
    $deadline=(Get-Date).AddSeconds(60)
    do {Start-Sleep -Seconds 1;if((Get-Date) -gt $deadline){throw 'Real menu did not load'}} until ((Get-Content "$prefix-app.log" -Raw) -match 'Menu data loaded')
    $rows+=Sample 'menu-live' $false
    & "$PSScriptRoot/capture.ps1" "ui-port/u3/$Tag/menu.png" | Out-Null
    Command 'shell-gallery'
    $rows+=Sample 'gallery-after-menu-held' $true
    Command 'shell-back'
    $rows+=Sample 'back-menu-live' $false
    Command 'shell-game-probe' 1
    $rows+=Sample 'simulated-running-game-held' $true
    Command 'shell-game-probe' 0
    $rows+=Sample 'game-ended-menu-live' $false
    $rows|ConvertTo-Json|Set-Content "$out/phases.json"
    Stop-Prototype $p $handle
    "Exit=$($p.ExitCode)"
} finally {if(!$p.HasExited){Stop-Prototype $p $handle};Remove-Item Env:RDI_PROTO_CONTROL -ErrorAction SilentlyContinue}
