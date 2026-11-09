param([double]$Scale=1,[string]$Tag="slint")
. "$PSScriptRoot/p5-common.ps1"
$out="evidence/ui-port/u4b0/$Tag-scale$Scale"
New-Item -ItemType Directory -Force $out|Out-Null
$control=Join-Path $PWD "$out/control.json"
$env:RDI_PROTO_CONTROL=$control
$app=Start-Process ./target/release/rdi-slint-proto.exe -ArgumentList @('--gallery','--scale',"$Scale",'--scene-memory-probe','--scene-probe-hold-ms=0') -PassThru -RedirectStandardOutput "$out/app.log" -RedirectStandardError "$out/app.err"
Remove-Item Env:RDI_PROTO_CONTROL
try {
 Start-Sleep -Seconds 4
 $handle=[P5Window]::Find($app.Id)
 Require-Focus $handle
 [P5Window]::Dpi($handle,[uint32](96*$Scale))
 [P5Window]::SetWindowPos($handle,[IntPtr]::Zero,40,40,(1280*$Scale),(720*$Scale),20)|Out-Null
 $id=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
 foreach($name in @('empty','prefilled','history','history-empty','submitting','error','register')) {
  $id++
  @{id=$id;action='login-fixture';name=$name}|ConvertTo-Json|Set-Content "$control.tmp"
  Move-Item "$control.tmp" $control -Force
  $deadline=[DateTime]::UtcNow.AddSeconds(8)
  do {
   Start-Sleep -Milliseconds 200
   $ack=[regex]::Replace((Get-Content "$out/app.log" -Raw),'\x1b\[[0-9;]*m','')
   if($ack -match "Runtime control applied\s+id=$id\b"){break}
   if([DateTime]::UtcNow -gt $deadline){throw 'Fixture was not acknowledged'}
  }while($true)
  Start-Sleep -Milliseconds 500
  $log=[regex]::Replace((Get-Content "$out/app.log" -Raw),'\x1b\[[0-9;]*m','')
  if($log -match 'Unknown runtime control'){throw 'Wrong binary: login fixture not supported'}
  Save-Window $handle "$out/$name.png"
 }
} finally { Stop-Prototype $app $handle }
