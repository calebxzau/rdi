$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot)
$latest=(Get-Item src/packs.rs).LastWriteTimeUtc
$deadline=[DateTime]::UtcNow.AddMinutes(5)
do {
    $exitFile=Get-Item evidence/ui-port/u4a/rust.exit.json
    if($exitFile.LastWriteTimeUtc -gt $latest) {
        $result=Get-Content $exitFile.FullName -Raw|ConvertFrom-Json
        if($result.tests -ne 0 -or $result.clippy -ne 0 -or $result.release -ne 0){throw 'Final Rust validation failed'}
        break
    }
    if([DateTime]::UtcNow -gt $deadline){throw 'Timed out waiting for final build'}
    Start-Sleep -Seconds 2
}while($true)
foreach($scale in @(1,2)){ & "$PSScriptRoot/u4a-visuals.ps1" -Scale $scale -Tag final-visual-r3 }
0|Set-Content evidence/ui-port/u4a/slint-visuals.exit
