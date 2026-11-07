param([string]$Screen='install-source',[double]$Scale=1,[string]$Tag='reference')
. "$PSScriptRoot/p5-common.ps1"
$out="evidence/ui-port/u4a/$Tag-scale$Scale"
New-Item -ItemType Directory -Force $out|Out-Null
$repo=(Resolve-Path ../..).Path
$cp=(Get-Content ../ui/build/u4a-reference-classpath.txt -Raw).Trim()
$argumentFile=(Join-Path $PWD "$out/$Screen.args")
$escaped=$cp.Replace('\','\\').Replace('"','\"')
[IO.File]::WriteAllText($argumentFile,"-Dsun.java2d.uiScale=1`n-cp`n`"$escaped`"`ncalebxzhou.rdi.client.ui.screen.U4aReferenceKt`n$Screen`n$Scale`n",[Text.UTF8Encoding]::new($false))
$app=Start-Process (Get-Command java).Source -ArgumentList "@$argumentFile" -WorkingDirectory "$repo/client/ui/run" -PassThru -RedirectStandardOutput "$out/$Screen.log" -RedirectStandardError "$out/$Screen.err"
try {
    $deadline=[DateTime]::UtcNow.AddSeconds(20)
    do {
        Start-Sleep -Milliseconds 300
        $app.Refresh()
        if($app.HasExited){throw "Reference exited: $($app.ExitCode)"}
        $handle=$app.MainWindowHandle
    }while($handle -eq [IntPtr]::Zero -and [DateTime]::UtcNow -lt $deadline)
    if($handle -eq [IntPtr]::Zero){throw 'No Compose reference window'}
    [P5Window]::SetWindowPos($handle,[IntPtr]::Zero,40,40,(1280*$Scale),(720*$Scale),20)|Out-Null
    [P5Window]::Activate($handle)|Out-Null
    Start-Sleep -Seconds 2
    Save-Window $handle "$out/$Screen.png"
} finally {
    $app.Refresh()
    if(!$app.HasExited){$app.CloseMainWindow()|Out-Null;if(!$app.WaitForExit(3000)){Stop-Process -Id $app.Id}}
}
