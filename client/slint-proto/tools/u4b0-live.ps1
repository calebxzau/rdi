. "$PSScriptRoot/p5-common.ps1"
Add-Type -AssemblyName System.Windows.Forms
Add-Type @'
using System;using System.Runtime.InteropServices;
public static class B0Mouse {
 [DllImport("user32.dll")] public static extern bool SetCursorPos(int x,int y);
 [DllImport("user32.dll")] public static extern void mouse_event(uint flags,uint dx,uint dy,uint data,UIntPtr extra);
 public static void Click(){mouse_event(2,0,0,0,UIntPtr.Zero);mouse_event(4,0,0,0,UIntPtr.Zero);}
}
'@
$out='evidence/ui-port/u4b0/live'
New-Item -ItemType Directory -Force $out|Out-Null
$run=Join-Path $PWD "$out/service-run-$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())"
New-Item -ItemType Directory -Force $run|Out-Null
$java=(Get-Command java).Source
$libs=(Resolve-Path '../ui/ui-service/build/install/ui-service/lib').Path+'/*'
# Isolated service working data, using the real installed service and real login API.
$env:RDI_DATA_DIR=$run
$env:RDI_SERVICE_COMMAND=ConvertTo-Json -Compress -InputObject @($java,"-Duser.dir=$run",'-Djava.awt.headless=true','-cp',$libs,'calebxzau.rdi.service.MainKt')
Remove-Item Env:RDI_PROTO_USR,Env:RDI_PROTO_PWD -ErrorAction SilentlyContinue
$control=Join-Path $PWD "$out/control.json"
$env:RDI_PROTO_CONTROL=$control
$script:app=$null;$script:handle=[IntPtr]::Zero
function Start-App($name) {
 $script:log="$out/$name.log"
 $script:app=Start-Process ./target/release/rdi-slint-proto.exe -ArgumentList @('--scale','1','--scene-memory-probe','--scene-probe-hold-ms=0') -PassThru -RedirectStandardOutput $script:log -RedirectStandardError "$out/$name.err"
 Start-Sleep -Seconds 5
 $script:handle=[P5Window]::Find($app.Id)
 Require-Focus $handle
 [P5Window]::Dpi($handle,96)
 [P5Window]::SetWindowPos($handle,[IntPtr]::Zero,40,40,1280,720,20)|Out-Null
}
function Beat {
 $text=[regex]::Replace((Get-Content $script:log -Raw),'\x1b\[[0-9;]*m','')
 $beats=[regex]::Matches($text,'Runtime probe data=(\{[^\r\n]+\})')
 if(!$beats.Count){return $null};$beats[$beats.Count-1].Groups[1].Value|ConvertFrom-Json
}
function Wait-State($predicate,$name) {
 $until=[DateTime]::UtcNow.AddSeconds(65)
 do {Start-Sleep -Milliseconds 500;$b=Beat;if($b -and (& $predicate $b)){return $b}}while([DateTime]::UtcNow -lt $until)
 throw "Timed out: $name"
}
function Click($x,$y) {
 Require-Focus $handle
 $rect=New-Object P5Window+RECT
 [P5Window]::GetWindowRect($handle,[ref]$rect)|Out-Null
 [B0Mouse]::SetCursorPos(($rect.L+$x),($rect.T+$y))|Out-Null
 Start-Sleep -Milliseconds 100
 [B0Mouse]::Click()
 Start-Sleep -Milliseconds 500
}
function Type-Text([string]$text) {
 [Windows.Forms.SendKeys]::SendWait('^a')
 [Windows.Forms.Clipboard]::SetText($text)
 [Windows.Forms.SendKeys]::SendWait('^v')
 Start-Sleep -Milliseconds 300
 [Windows.Forms.Clipboard]::Clear()
 Start-Sleep -Milliseconds 500
}
$checks=[Collections.Generic.List[string]]::new()
try {
 Start-App 'typed'
 Wait-State {param($b) $b.login_visible -and !$b.login_busy} 'empty login'|Out-Null
 Save-Window $handle "$out/empty.png"
 $checks.Add('startup without credential variables shows login')
 # Native keyboard and field input, no password written to a control file or log.
 $secret=[IO.File]::ReadAllLines('\\wsl.localhost\Debian\home\calebxzhou\.config\rdi\.test_usr_pwd')
 Click 570 320;Type-Text $secret[0]
 Click 570 380;Type-Text 'intentionally-wrong-b0'
 [Windows.Forms.SendKeys]::SendWait('{ENTER}')
 Wait-State {param($b) $b.login_visible -and !$b.login_busy -and $b.modal -and $b.action_error -ne '未填写完整'} 'wrong password dialog'|Out-Null
 Save-Window $handle "$out/wrong-password.png"
 $checks.Add('wrong password displays retryable dialog')
 [Windows.Forms.SendKeys]::SendWait('{ESC}')
 Click 570 380;Type-Text $secret[1]
 [Windows.Forms.SendKeys]::SendWait('{ENTER}')
 Wait-State {param($b) !$b.login_visible -and !$b.modal} 'typed login'|Out-Null
 Start-Sleep -Seconds 3
 Save-Window $handle "$out/menu.png"
 $checks.Add('native typed credentials and Enter log in')
 Stop-Prototype $app $handle
 Start-App 'restart'
 Wait-State {param($b) !$b.login_visible -and !$b.modal} 'saved auto login'|Out-Null
 $checks.Add('restart without credential variables auto logs in from isolated saved account')
 Save-Window $handle "$out/restart.png"
 Stop-Prototype $app $handle
 $credentialFile=Join-Path $run 'local_credentials.json'
 $saved=Get-Content $credentialFile -Raw|ConvertFrom-Json -AsHashtable
 $saved['autoLoginDisabled']=$true
 $saved|ConvertTo-Json -Depth 10|Set-Content $credentialFile
 Start-App 'prefill'
 Wait-State {param($b) $b.login_visible -and !$b.login_busy -and $b.login_saved} 'saved prefill without auto login'|Out-Null
 Save-Window $handle "$out/prefilled.png"
 $checks.Add('disabled auto login prefills saved account without exposing password')
 Click 570 435
 Wait-State {param($b) !$b.login_visible -and !$b.modal} 'saved selection login'|Out-Null
 $checks.Add('saved-account login button reaches menu')
 $checks|ConvertTo-Json|Set-Content "$out/checks.json"
} finally {
 if($app){Stop-Prototype $app $handle}
 Remove-Item Env:RDI_PROTO_CONTROL,Env:RDI_SERVICE_COMMAND,Env:RDI_DATA_DIR -ErrorAction SilentlyContinue
 [Windows.Forms.Clipboard]::Clear()
 $secret=$null
}
