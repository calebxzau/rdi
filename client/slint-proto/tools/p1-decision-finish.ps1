$ErrorActionPreference='Continue'
$PSNativeCommandUseErrorActionPreference=$false
Set-Location (Split-Path $PSScriptRoot -Parent)
while(!(Select-String 'Finished `release`|error: could not compile' evidence/bg-port/p1/release.log -Quiet)){Start-Sleep -Seconds 1}
cargo fmt *> evidence/bg-port/p1/fmt.log
cargo test *> evidence/bg-port/p1/test.log
Set-Content evidence/bg-port/p1/test.exit $LASTEXITCODE
cargo clippy --all-targets -- -D warnings *> evidence/bg-port/p1/clippy.log
Set-Content evidence/bg-port/p1/clippy.exit $LASTEXITCODE
cargo build --release --bins --examples *> evidence/bg-port/p1/release-final.log
Set-Content evidence/bg-port/p1/release-final.exit $LASTEXITCODE
if($LASTEXITCODE -eq 0){
 & pwsh.exe -NoProfile -File tools/p1-decision-previews.ps1
 foreach($case in @('static','best','live')){
  Set-Content evidence/bg-port/p1/current-case.txt $case
  & pwsh.exe -NoProfile -File tools/p1-decision-measure.ps1 -Case $case
 }
 Set-Content evidence/bg-port/p1/current-case.txt 'complete'
}
