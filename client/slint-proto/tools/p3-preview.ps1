$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
if((Get-Content evidence/bg-port/p3/release.exit).Trim() -ne '0'){throw 'Release build did not pass'}
foreach($case in @('final','diagnostic4','diagnostic-full','p2-preserved')) {
    $flags=@('--capture-only');$times=@('0')
    switch($case) {
        'final' {$flags=@('--capture-hdr');$times=@('0','15','45')}
        'diagnostic4' {$flags+='--scene-msaa=4'}
        'diagnostic-full' {$flags+='--scene-full-vertices'}
        'p2-preserved' {$flags+='--scene-p2-only';$times=@('0','15','45')}
    }
    & ./target/release/examples/scene_preview.exe "evidence/bg-port/p3/$case" @times @flags *> "evidence/bg-port/p3/$case.log"
    Set-Content "evidence/bg-port/p3/$case.exit" $LASTEXITCODE
    if($LASTEXITCODE -ne 0){throw "Preview $case failed"}
}
