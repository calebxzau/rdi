param([string]$Tag = 'interaction', [switch]$ScrollOnly)
. "$PSScriptRoot/p5-common.ps1"
Add-Type -AssemblyName System.Windows.Forms
Add-Type @'
using System;using System.Runtime.InteropServices;
public static class U3Mouse {
 [DllImport("user32.dll")] public static extern bool SetCursorPos(int x,int y);
 [DllImport("user32.dll")] public static extern void mouse_event(uint flags,uint dx,uint dy,uint data,UIntPtr extra);
 public static void Down(){mouse_event(2,0,0,0,UIntPtr.Zero);}
 public static void Up(){mouse_event(4,0,0,0,UIntPtr.Zero);}
}
'@
$out="evidence/ui-port/u3/$Tag"
if(Test-Path $out){throw 'Choose a new tag to preserve evidence'}
New-Item -ItemType Directory -Force $out|Out-Null
Get-FileHash target/release/rdi-slint-proto.exe | Select-Object Algorithm,Hash | ConvertTo-Json | Set-Content "$out/binary.json"
$prefix="$out/run"
$control=Join-Path (Resolve-Path $out).Path 'control.json'
$env:RDI_PROTO_CONTROL=$control
$script:commandId=0
function Command([string]$Action,[double]$Value=0){$script:commandId++;@{id=$script:commandId;action=$Action;value=$Value}|ConvertTo-Json -Compress|Set-Content $control;Start-Sleep -Milliseconds 1600}
function Beat {
 $text=[regex]::Replace((Get-Content "$prefix-app.log" -Raw),'\x1b\[[0-9;]*m','')
 $beats=[regex]::Matches($text,'Runtime probe data=(\{[^\r\n]+\})')
 if(!$beats.Count){throw 'Missing heartbeat'}
 $beats[$beats.Count-1].Groups[1].Value|ConvertFrom-Json
}
function Click([int]$X,[int]$Y){
 if (!$ScrollOnly) {Require-Focus $handle}
 $point=[IntPtr]::new(($Y -shl 16) -bor $X)
 [P5Window]::PostMessage($handle,0x200,[IntPtr]::Zero,$point)|Out-Null
 Start-Sleep -Milliseconds 150
 [P5Window]::PostMessage($handle,0x201,[IntPtr]::new(1),$point)|Out-Null
 Start-Sleep -Milliseconds 150
 [P5Window]::PostMessage($handle,0x202,[IntPtr]::Zero,$point)|Out-Null
 Start-Sleep -Milliseconds 2000
}
function Drag([int]$X,[int]$Y,[int]$ToX,[int]$ToY){
 Require-Focus $handle
 $r=New-Object P5Window+RECT;[P5Window]::GetWindowRect($handle,[ref]$r)|Out-Null
 [U3Mouse]::SetCursorPos(($r.L+$X),($r.T+$Y))|Out-Null
 Start-Sleep -Milliseconds 200
 [U3Mouse]::Down();Start-Sleep -Milliseconds 200
 foreach($step in 1..10){
  $nextX=[int]($r.L+$X+($ToX-$X)*$step/10);$nextY=[int]($r.T+$Y+($ToY-$Y)*$step/10)
  [U3Mouse]::SetCursorPos($nextX,$nextY)|Out-Null
  Start-Sleep -Milliseconds 80
 }
 [U3Mouse]::Up()
 Start-Sleep -Milliseconds 1800
}

function Paste([string]$Text){[System.Windows.Forms.Clipboard]::SetText($Text);[System.Windows.Forms.SendKeys]::SendWait('^a');[System.Windows.Forms.SendKeys]::SendWait('^v');Start-Sleep -Milliseconds 1400}
function Assert([bool]$Condition,[string]$Name){if(!$Condition){$foreground=[P5Window]::ForegroundPid(); if($foreground){(Get-Process -Id $foreground).ProcessName|Set-Content "$out/failure-foreground.txt"}; & "$PSScriptRoot/capture.ps1" "ui-port/u3/$Tag/failure.png"|Out-Null; throw "Failed: $Name"};$script:checks.Add($Name)}
$checks=[Collections.Generic.List[string]]::new()
$arguments=@('--gallery','--scale','1','--scene-memory-probe','--scene-probe-hold-ms=0')
if ($ScrollOnly) {$arguments += '--scene-probe-unfocused'}
$p=Start-Process ./target/release/rdi-slint-proto.exe -PassThru -ArgumentList $arguments -RedirectStandardOutput "$prefix-app.log" -RedirectStandardError "$prefix-app.err"
$handle=[IntPtr]::Zero
try {
 Start-Sleep -Seconds 6;$handle=[P5Window]::Find($p.Id);if (!$ScrollOnly) {Require-Focus $handle}
 [P5Window]::Dpi($handle,96);Start-Sleep -Milliseconds 400
 [P5Window]::SetWindowPos($handle,[IntPtr]::Zero,40,40,1280,720,20)|Out-Null
 Start-Sleep -Seconds 2
 Assert ((Beat).scale -eq 1) 'native-scale-1'
 if ($ScrollOnly) {
  Command 'gallery-page' 1
  Drag 736 260 736 450
  Assert ((Beat).content_scroll -gt 100) 'vertical-scrollbar-drag'
  Command 'gallery-page' 3
  Drag 100 591 300 591
  Assert ((Beat).tile_scroll -lt -100) 'horizontal-scrollbar-drag'
  & "$PSScriptRoot/capture.ps1" "ui-port/u3/$Tag/tiles-scrolled.png"|Out-Null
  $checks|ConvertTo-Json|Set-Content "$out/checks.json"
  Stop-Prototype $p $handle
  "Exit=$($p.ExitCode)"
  return
 }
 & "$PSScriptRoot/capture.ps1" "ui-port/u3/$Tag/before.png"|Out-Null
 Click 1210 316
 & "$PSScriptRoot/capture.ps1" "ui-port/u3/$Tag/password-reveal.png"|Out-Null
 Assert ((Beat).password_revealed) 'password-reveal'
 Click 1210 316
 Assert (!(Beat).password_revealed) 'password-mask'
 Click 800 235
 Paste '与朋友一起探索Minecraft1.21.1'
 & "$PSScriptRoot/capture.ps1" "ui-port/u3/$Tag/input-paste.png"|Out-Null
 [System.Windows.Forms.SendKeys]::SendWait('{ENTER}');Start-Sleep -Milliseconds 1200
 & "$PSScriptRoot/capture.ps1" "ui-port/u3/$Tag/input-enter.png"|Out-Null
 Assert ((Beat).toast_visible) 'field-enter'
 Click 120 462
 Assert ((Beat).modal -and (Beat).dialog_focused) 'dialog-open-focus'
 [System.Windows.Forms.SendKeys]::SendWait('{TAB 7}');Start-Sleep -Milliseconds 1200
 Assert ((Beat).modal -and (Beat).dialog_focused) 'dialog-tab-trap'
 [System.Windows.Forms.SendKeys]::SendWait('{ESC}');Start-Sleep -Milliseconds 1200
 Assert (!(Beat).modal) 'dialog-escape'
 Command 'gallery-page' 1
 Click 200 150
 Paste '村庄'
 Assert ((Beat).content_rows -eq 8) 'content-search'
 Click 137 207
 Click 200 150
 Paste '资源包'
 Assert ((Beat).content_rows -eq 0) 'content-type-filter'
 Click 66 207
 Assert ((Beat).content_rows -eq 8) 'content-all-filter'
 Click 200 150
 [System.Windows.Forms.SendKeys]::SendWait('^a{BACKSPACE}');Start-Sleep -Milliseconds 1400
 Assert ((Beat).content_rows -eq 24) 'content-clear-search'
 Drag 736 260 736 450
 Assert ((Beat).content_scroll -gt 100) 'vertical-scrollbar-drag'
 Click 530 146
 Click 625 146
 & "$PSScriptRoot/capture.ps1" "ui-port/u3/$Tag/icon-mode.png"|Out-Null
 Command 'gallery-page' 2
 Click 380 145
 Assert ([System.Windows.Forms.Clipboard]::GetText().Contains('[12:34:00] [INFO]')) 'console-copy'
 Click 451 145
 Assert (@(Get-ChildItem evidence/ui-port/u3/gallery-console-*.log).Count -gt 0) 'console-export'
 Click 720 260
 Assert ((Beat).task_rows -eq 1) 'task-collapse'
 Click 720 260
 Assert ((Beat).task_rows -eq 6) 'task-expand'
 Click 525 145
 Assert ((Beat).log_rows -eq 0) 'console-clear'
 & "$PSScriptRoot/capture.ps1" "ui-port/u3/$Tag/console-empty.png"|Out-Null
 Command 'gallery-page' 3
 Drag 100 591 300 591
 Assert ((Beat).tile_scroll -lt -100) 'horizontal-scrollbar-drag'
 & "$PSScriptRoot/capture.ps1" "ui-port/u3/$Tag/tiles-scrolled.png"|Out-Null
 $checks|ConvertTo-Json|Set-Content "$out/checks.json"
 Stop-Prototype $p $handle
 "Exit=$($p.ExitCode)"
} finally {if(!$p.HasExited){Stop-Prototype $p $handle};$checks|ConvertTo-Json|Set-Content "$out/checks.json";Remove-Item Env:RDI_PROTO_CONTROL -ErrorAction SilentlyContinue}
