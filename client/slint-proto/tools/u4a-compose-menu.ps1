param([double]$Scale=2)
$ErrorActionPreference='Stop'
. "$PSScriptRoot/p5-common.ps1"
$out='evidence/ui-port/u4a/compose-menu'
New-Item -ItemType Directory -Force $out|Out-Null
$repo=(Resolve-Path ../..).Path
$cp=(Get-Content ../ui/build/u4a-reference-classpath.txt -Raw).Trim()
$argumentFile=Join-Path $PWD "$out/run.args"
$escaped=$cp.Replace('\','\\').Replace('"','\"')
[IO.File]::WriteAllText($argumentFile,"-Dsun.java2d.uiScale=$Scale`n-Drdi.noUpdate=true`n-cp`n`"$escaped`"`ncalebxzau.rdi.client.MainKt`n",[Text.UTF8Encoding]::new($false))
$app=Start-Process (Get-Command java).Source -ArgumentList "@$argumentFile" -WorkingDirectory "$repo/client/ui/run" -PassThru -RedirectStandardOutput "$out/app.log" -RedirectStandardError "$out/app.err"
@{app=$app.Id}|ConvertTo-Json|Set-Content "$out/run.json"
try {
    $deadline=[DateTime]::UtcNow.AddSeconds(30)
    do {
        Start-Sleep -Milliseconds 300
        $app.Refresh()
        if($app.HasExited){throw 'Compose launcher exited'}
        $handle=$app.MainWindowHandle
    }while($handle -eq [IntPtr]::Zero -and [DateTime]::UtcNow -lt $deadline)
    if($handle -eq [IntPtr]::Zero){throw 'No Compose window'}
    [P5Window]::Activate($handle)|Out-Null
    Start-Sleep -Seconds 5
    Save-Window $handle "$out/start.png"
    0|Set-Content "$out/start.exit"
} catch {
    $app.CloseMainWindow()|Out-Null
    throw
}
