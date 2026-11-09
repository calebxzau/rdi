param([double]$Scale=1)
. "$PSScriptRoot/p5-common.ps1"
$out="evidence/ui-port/u4b0/compose-scale$Scale"
New-Item -ItemType Directory -Force $out|Out-Null
$cp=(Get-Content ../ui/build/u4b0-reference-classpath.txt -Raw).Trim()
foreach($name in @('empty','prefilled','history','history-empty','submitting','error','register')) {
 $argsFile=Join-Path $PWD "$out/$name.args"
 $escaped=$cp.Replace('\','\\').Replace('"','\"')
 [IO.File]::WriteAllText($argsFile,"-Dsun.java2d.uiScale=1`n-cp`n`"$escaped`"`ncalebxzhou.rdi.client.ui.screen.ReferenceMainKt`n$name`n$Scale`n",[Text.UTF8Encoding]::new($false))
 $app=Start-Process (Get-Command java).Source -ArgumentList "@$argsFile" -WorkingDirectory (Join-Path $PWD $out) -PassThru -RedirectStandardOutput "$out/$name.log" -RedirectStandardError "$out/$name.err"
 try {
  Start-Sleep -Seconds 5
  $app.Refresh();$handle=$app.MainWindowHandle
  if($handle -eq [IntPtr]::Zero){throw 'No Compose reference window'}
  [P5Window]::SetWindowPos($handle,[IntPtr]::Zero,40,40,(1280*$Scale),(720*$Scale),20)|Out-Null
  [P5Window]::Activate($handle)|Out-Null
  Start-Sleep -Seconds 1
  Save-Window $handle "$out/$name.png"
 } finally {if(!$app.HasExited){$app.CloseMainWindow()|Out-Null;if(!$app.WaitForExit(3000)){Stop-Process -Id $app.Id}}}
}
