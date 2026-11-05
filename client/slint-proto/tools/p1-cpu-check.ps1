$ErrorActionPreference='Continue'
$PSNativeCommandUseErrorActionPreference=$false
Set-Location (Split-Path $PSScriptRoot -Parent)
cargo fmt *> evidence/bg-port/p1/full/fmt.log
cargo test --lib *> evidence/bg-port/p1/full/cpu-test.log
Set-Content evidence/bg-port/p1/full/cpu-test.exit $LASTEXITCODE
