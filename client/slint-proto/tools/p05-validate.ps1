$ErrorActionPreference='Continue'
$PSNativeCommandUseErrorActionPreference=$false
Set-Location (Split-Path $PSScriptRoot -Parent)
cargo fmt *> evidence/bg-port/p0.5/fmt.log
cargo test *> evidence/bg-port/p0.5/test.log
Set-Content evidence/bg-port/p0.5/test.exit $LASTEXITCODE
cargo clippy --all-targets -- -D warnings *> evidence/bg-port/p0.5/clippy.log
Set-Content evidence/bg-port/p0.5/clippy.exit $LASTEXITCODE
cargo build --release --bins --examples *> evidence/bg-port/p0.5/release.log
Set-Content evidence/bg-port/p0.5/release.exit $LASTEXITCODE

if($LASTEXITCODE -eq 0 -and $env:RDI_PROTO_USR -and $env:RDI_PROTO_PWD){ & pwsh.exe -NoProfile -File tools/p05-measure-all.ps1 }
