param([switch]$SkipMemory)
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
Write-Output 'J1 live: begin'
$out='evidence/jvm-removal/j1/live-' + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
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
$script:started=$null
function Start-App($name) {
 Write-Output "J1 live: starting $name"
 $script:started=[DateTime]::UtcNow
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
$realFile=(Resolve-Path '../ui/run').Path+'/local_credentials.json'
$beforeHash=(Get-FileHash $realFile -Algorithm SHA256).Hash
$beforeTime=(Get-Item $realFile).LastWriteTimeUtc.Ticks
function Services { @(Get-CimInstance Win32_Process | Where-Object {$_.Name -match '^java(w)?\.exe$' -and $_.CommandLine -match 'calebxzau.rdi.service.MainKt'}) }
function OwnService { @(Services | Where-Object {$_.ParentProcessId -eq $app.Id}) }
function Assert-Packs {
 Click 720 84
 Wait-State {param($b) $b.selected_tab -eq 1 -and !$b.packs_loading -and !$b.packs_error} 'pack tab session handover'|Out-Null
 $checks.Add('installed packs request through session.set succeeded')
}
try {
 Start-App 'typed'
 Wait-State {param($b) $b.login_visible -and !$b.login_busy} 'empty login'|Out-Null
 $checks.Add('usable login observed by native automation at '+([DateTime]::UtcNow-$script:started).TotalMilliseconds+'ms (includes fixed5s wait)')
 Write-Output 'J1 live: empty login ready'
 Save-Window $handle "$out/empty.png"
 if((Services).Count){throw 'A service exists before sign-in'}
 $checks.Add('startup shows login with no service process')
 $text=[regex]::Replace((Get-Content $script:log -Raw),'\x1b\[[0-9;]*m','')
 $ready=[regex]::Match($text,'Runtime probe data=(\{[^\r\n]+\})')
 if($ready.Success){$checks.Add('login page usable before first native submit; first-frame timing in typed.log')}
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
 if(!(OwnService).Count){throw 'No service after sign-in'}
 $checks.Add('native typed credentials and Enter log in; service exists')
 $until=[DateTime]::UtcNow.AddSeconds(30)
 do {Start-Sleep -Milliseconds 500;$text=[regex]::Replace((Get-Content $script:log -Raw),'\x1b\[[0-9;]*m','')}while($text -notmatch 'Menu data loaded' -and $text -notmatch 'hosts=100' -and [DateTime]::UtcNow -lt $until)

 Assert-Packs
 Save-Window $handle "$out/packs.png"
 Click 560 84
 # Focused release samples: warmup then two60s/30s/10s cycles, UI/service separate.
 $samples=[Collections.Generic.List[object]]::new()
 $start=Get-Date
 for($cycle=0;$cycle -lt 3 -and !$SkipMemory;$cycle++) {
  Write-Output "J1 live: memory cycle $cycle"
  Require-Focus $handle
  1..3|ForEach-Object {Start-Sleep -Seconds 20}
  Require-Focus $handle
  $samples.Add((Process-Sample $app $handle "visible-$cycle" $start))
  $service=Get-Process -Id (OwnService)[0].ProcessId
  $service.Refresh()
  $samples.Add([pscustomobject]@{Phase="service-$cycle";WorkingSet=$service.WorkingSet64;Private=$service.PrivateMemorySize64})
  [P5Window]::ShowWindow($handle,6)|Out-Null
  Start-Sleep -Seconds 30
  $samples.Add((Process-Sample $app $handle "minimized-$cycle" $start))
  [P5Window]::ShowWindow($handle,9)|Out-Null
  Require-Focus $handle
  Start-Sleep -Seconds 10
 }
 $samples|ConvertTo-Json -Depth 8|Set-Content "$out/memory.json"
 Stop-Prototype $app $handle
 Start-App 'restart'
 Wait-State {param($b) !$b.login_visible -and !$b.modal} 'saved auto login'|Out-Null
 $checks.Add('restart without credential variables auto logs in from isolated saved account')
 Save-Window $handle "$out/restart.png"
 Stop-Prototype $app $handle
 $credentialFile=Join-Path $run 'local_credentials.json'
 $saved=Get-Content $credentialFile -Raw|ConvertFrom-Json -AsHashtable
 $saved['autoLoginDisabled']=$true
 $saved['lastPlayHost']=@{id='j1-fake-room';name='验收房间'}
 $saved['j1Unknown']=@{preserved=$true}
 $saved|ConvertTo-Json -Depth 10|Set-Content $credentialFile
 Start-App 'prefill'
 Wait-State {param($b) $b.login_visible -and !$b.login_busy -and $b.login_saved} 'saved prefill without auto login'|Out-Null
 Save-Window $handle "$out/prefilled.png"
 $checks.Add('disabled auto login prefills saved account without exposing password')
 Click 570 435
 Wait-State {param($b) !$b.login_visible -and !$b.modal} 'saved selection login'|Out-Null
 $checks.Add('saved-account login button reaches menu')
 $after=Get-Content $credentialFile -Raw|ConvertFrom-Json -AsHashtable
 if($after.lastPlayHost.id -ne 'j1-fake-room' -or !$after.j1Unknown.preserved){throw 'Credential fields lost'}
 $checks.Add('lastPlayHost and unknown top-level field survived')
 Assert-Packs
 $service=(OwnService)[0]
 Stop-Process -Id $service.ProcessId -Force
 $until=[DateTime]::UtcNow.AddSeconds(65)
 do {Start-Sleep -Milliseconds 500;$replacement=OwnService}while((!$replacement.Count -or $replacement[0].ProcessId -eq $service.ProcessId) -and [DateTime]::UtcNow -lt $until)
 if(!$replacement.Count -or $replacement[0].ProcessId -eq $service.ProcessId){throw 'Service did not restart'}
 Wait-State {param($b) !$b.login_visible -and !$b.modal} 'service recovery login'|Out-Null
 Start-Sleep -Seconds 5
 Assert-Packs
 $checks.Add('service kill recovered through login and pack tab works')
 if((Get-FileHash $realFile -Algorithm SHA256).Hash -ne $beforeHash -or (Get-Item $realFile).LastWriteTimeUtc.Ticks -ne $beforeTime){throw 'Owner credentials changed'}
 $checks.Add('owner credentials timestamp and SHA256 unchanged')
 foreach($name in 'typed','restart','prefill'){if((Get-Content "$out/$name.log" -Raw) -match 'config.toml格式无效'){throw "Kotlin-written config.toml rejected in $name"}}
 $checks.Add('Kotlin-written config.toml with null values parsed without warning')
 # Capture JWT in memory only, over TLS. Never place it in a control file or evidence.
 $body=@{usr=$secret[0];pwd=$secret[1]}|ConvertTo-Json -Compress
 $response=Invoke-WebRequest 'https://rdi.calebxzhou.cn:65331/player/login' -Method Post -ContentType 'application/json' -Body $body
 $jwt=[string]$response.Headers.jwt
 if([string]::IsNullOrWhiteSpace($jwt)){throw 'Secret scan could not acquire reference token'}
 Stop-Prototype $app $handle
 $app=$null
 Start-Sleep -Seconds 2
 $leaked=$false
 Get-ChildItem $out -Recurse -File | Where-Object {$_.Extension -in '.log','.err','.json','.txt','.md' -and $_.Name -ne 'local_credentials.json'} | ForEach-Object {
  $stream=[IO.File]::Open($_.FullName,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::ReadWrite)
  $reader=[IO.StreamReader]::new($stream)
  try {$text=$reader.ReadToEnd()} finally {$reader.Dispose()}
  if($text.Contains($secret[1]) -or ($jwt -and $text.Contains($jwt)) -or $text -match 'eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+'){$leaked=$true}
 }
 if($leaked){throw 'Secret scan failed (secret values omitted)'}
 $checks.Add('password and JWT scan passed; credential store excluded by design')
 $jwt=$null;$response=$null;$body=$null
 $checks|ConvertTo-Json|Set-Content "$out/checks.json"
 Write-Output "J1 live: passed; evidence $out"
} finally {
 if($app){Stop-Prototype $app $handle}
 Remove-Item Env:RDI_PROTO_CONTROL,Env:RDI_SERVICE_COMMAND,Env:RDI_DATA_DIR -ErrorAction SilentlyContinue
 [Windows.Forms.Clipboard]::Clear()
 $secret=$null
 if((Get-FileHash $realFile -Algorithm SHA256).Hash -ne $beforeHash -or (Get-Item $realFile).LastWriteTimeUtc.Ticks -ne $beforeTime){Write-Error "Owner credentials changed"}
}
