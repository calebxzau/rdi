$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
foreach($case in @('default','reflection-first','skip-bloom','skip-fog','skip-copy','skip-mip','skip-final','unrendered')){
 $flags=@('--capture-only');$times=@('0')
 switch($case){
  'default' {$flags=@('--capture-hdr');$times=@('0','15','45')}
  'reflection-first' {$flags+='--scene-player-reflection-first'}
  'unrendered' {$flags+='--scene-post-create-only'}
  default {$flags+="--scene-skip-post=$($case.Substring(5))"}
 }
 & ./target/release/examples/scene_preview.exe "evidence/bg-port/p5/preview/$case" @times @flags *> "evidence/bg-port/p5/preview-$case.log"
 Set-Content "evidence/bg-port/p5/preview-$case.exit" $LASTEXITCODE
 if($LASTEXITCODE -ne 0){throw "P5 preview $case failed"}
}
