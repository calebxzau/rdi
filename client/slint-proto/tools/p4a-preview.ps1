$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
if((Get-Content evidence/bg-port/p4a/release.exit).Trim() -ne '0'){throw 'P4a release not built'}
foreach($case in @('steve','slim-cape','legacy','hd','diagnostic4','diagnostic-full','p3-preserved')) {
 $flags=@('--capture-only','--dump-player');$times=@('0')
 switch($case) {
  'steve' {$flags=@('--capture-hdr','--dump-player');$times=@('0','15','45')}
  'slim-cape' {$flags+=@('--player-slim','--player-cape=evidence/bg-port/p4a/fixtures/cape.png');$times=@('0','15','45')}
  'legacy' {$flags+='--player-skin=evidence/bg-port/p4a/fixtures/legacy.png'}
  'hd' {$flags+='--player-skin=evidence/bg-port/p4a/fixtures/hd-steve.png'}
  'diagnostic4' {$flags+='--scene-msaa=4'}
  'diagnostic-full' {$flags+='--scene-full-vertices'}
  'p3-preserved' {$flags+='--scene-p3-only';$times=@('0','15','45')}
 }
 & ./target/release/examples/scene_preview.exe "evidence/bg-port/p4a/$case" @times @flags *> "evidence/bg-port/p4a/$case.log"
 Set-Content "evidence/bg-port/p4a/$case.exit" $LASTEXITCODE
 if($LASTEXITCODE -ne 0){throw "P4a preview $case failed"}
}
