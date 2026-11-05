$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
cargo build --release --bins --examples *> evidence/bg-port/p4a/final-validation/release-retry.log
Set-Content evidence/bg-port/p4a/final-validation/release-retry.exit $LASTEXITCODE
if($LASTEXITCODE -ne 0){throw 'Final release retry failed'}
Set-Content evidence/bg-port/p4a/final-validation/complete.exit 0
