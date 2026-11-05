$ErrorActionPreference='Continue'
$PSNativeCommandUseErrorActionPreference=$false
Set-Location (Split-Path $PSScriptRoot -Parent)
$cases=[ordered]@{default=@();msaa2=@('--scene-msaa=2');msaa1=@('--scene-msaa=1');packed=@('--scene-packed');chunk=@('--scene-chunk-upload');lazy=@('--scene-lazy-targets');best=@('--scene-msaa=1','--scene-packed','--scene-lazy-targets')}
foreach($case in $cases.Keys){
 $directory="evidence/bg-port/p0.5/preview-$case"
 & ./target/release/examples/scene_preview.exe $directory 0 15 45 @($cases[$case]) *> "evidence/bg-port/p0.5/preview-$case.log"
 Set-Content "evidence/bg-port/p0.5/preview-$case.exit" $LASTEXITCODE
}
