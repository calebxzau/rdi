param([int]$Scale = 1, [string]$Tag = 'gallery', [switch]$Passive)
. "$PSScriptRoot/p5-common.ps1"
$out = "evidence/ui-port/u3/$Tag-scale$Scale"
if (Test-Path $out) { throw 'Choose a new tag to preserve evidence' }
New-Item -ItemType Directory -Force $out | Out-Null
Get-FileHash target/release/rdi-slint-proto.exe | Select-Object Algorithm,Hash | ConvertTo-Json | Set-Content "$out/binary.json"
$prefix = "$out/run"
$control = (Join-Path (Resolve-Path $out).Path 'control.json')
$env:RDI_PROTO_CONTROL = $control
$script:commandId = 0
function Command([string]$Action, [double]$Value = 0) {
    $script:commandId++
    @{id=$script:commandId; action=$Action; value=$Value} | ConvertTo-Json -Compress | Set-Content $control
    Start-Sleep -Milliseconds 1500
}
function Capture([string]$Name) { & "$PSScriptRoot/capture.ps1" "ui-port/u3/$Tag-scale$Scale/$Name.png" | Out-Null }
$arguments = @('--gallery','--scale',"$Scale",'--scene-memory-probe','--scene-probe-hold-ms=0')
if ($Passive) {$arguments += '--scene-probe-unfocused'}
$p = Start-Process ./target/release/rdi-slint-proto.exe -PassThru -ArgumentList $arguments -RedirectStandardOutput "$prefix-app.log" -RedirectStandardError "$prefix-app.err"
$handle = [IntPtr]::Zero
try {
    Start-Sleep -Seconds 6
    if ($p.HasExited) { throw 'Gallery exited during startup' }
    $handle = [P5Window]::Find($p.Id)
    if (!$Passive) {Require-Focus $handle}
    [P5Window]::Dpi($handle, [uint32](96*$Scale))
    Start-Sleep -Milliseconds 500
    # Keep the logical review area identical at both scales.
    [P5Window]::SetWindowPos($handle,[IntPtr]::Zero,40,40,(1280*$Scale),(720*$Scale),20) | Out-Null
    Start-Sleep -Seconds 2
    $text = [regex]::Replace((Get-Content "$prefix-app.log" -Raw),'\x1b\[[0-9;]*m','')
    $beats = [regex]::Matches($text,'Runtime probe data=(\{[^\r\n]+\})')
    $beat = $beats[$beats.Count-1].Groups[1].Value | ConvertFrom-Json
    if ($beat.scale -ne $Scale -or $beat.width -ne 1280*$Scale -or $beat.height -ne 720*$Scale) {throw 'Actual scale/size mismatch; discard screenshots'}
    Capture 'buttons-fields'
    Command 'gallery-toast'
    Capture 'toast'
    Command 'gallery-dialog' 1
    Capture 'dialog'
    Command 'gallery-dialog' 0
    Command 'gallery-page' 1
    Capture 'content'
    foreach ($state in 1..3) {Command 'gallery-state' $state; Capture "content-state-$state"}
    Command 'gallery-state' 0
    Command 'gallery-page' 2
    Capture 'console-tasks'
    Command 'gallery-page' 3
    Capture 'glass-tiles'
    if (!$Passive) {Require-Focus $handle}
    $p.Refresh()
    [pscustomobject]@{scale=$Scale;focused=([P5Window]::GetForegroundWindow() -eq $handle);workingSetMiB=$p.WorkingSet64/1MB;privateMiB=$p.PrivateMemorySize64/1MB} | ConvertTo-Json | Set-Content "$out/process.json"
    [P5Window]::PostMessage($handle,0x10,[IntPtr]::Zero,[IntPtr]::Zero) | Out-Null
    if (!$p.WaitForExit(10000)) {throw 'Gallery close timed out'}
    "Exit=$($p.ExitCode)"
} finally {
    if (!$p.HasExited) {Stop-Prototype $p $handle}
    Remove-Item Env:RDI_PROTO_CONTROL -ErrorAction SilentlyContinue
}
