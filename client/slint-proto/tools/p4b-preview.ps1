$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$out='evidence/bg-port/p4b'
New-Item -ItemType Directory -Force "$out/preview" | Out-Null
$times=@('0','15','45')
$cases=[ordered]@{
 'steve'=@('--crowd=50','--dump-crowd');
 'mixed'=@('--crowd=50',"--crowd-skins=$out/fixtures/mixed",'--dump-crowd','--capture-only');
 'startup-steve'=@('--capture-only');
 'p4a-only'=@('--scene-p4a-only','--capture-only');
 'no-crowd'=@('--no-crowd');
 'diagnostic4'=@('--crowd=50','--scene-msaa=4','--capture-only');
 'diagnostic-full'=@('--crowd=50','--scene-full-vertices','--capture-only');
}
foreach($case in $cases.Keys){
 & ./target/release/examples/scene_preview.exe "$out/preview/$case" @times @($cases[$case]) *> "$out/preview-$case.log"
 Set-Content "$out/preview-$case.exit" $LASTEXITCODE
 Write-Output "$case exit=$LASTEXITCODE"
 if($LASTEXITCODE -ne 0){exit 1}
}
& ./evidence/bg-port/p4b/controls/p5-preview-accepted.exe "$out/preview/p5-control" @times --capture-only *> "$out/preview-p5-control.log"
Write-Output "p5-control exit=$LASTEXITCODE"
