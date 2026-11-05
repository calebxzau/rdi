$ErrorActionPreference = 'Continue'
$PSNativeCommandUseErrorActionPreference = $false
Set-Location (Split-Path $PSScriptRoot -Parent)
cargo fmt *> evidence/bg-port/p0-fmt.log
cargo test *> evidence/bg-port/p0-test.log
Set-Content evidence/bg-port/p0-test.exit $LASTEXITCODE
cargo clippy --all-targets -- -D warnings *> evidence/bg-port/p0-clippy.log
Set-Content evidence/bg-port/p0-clippy.exit $LASTEXITCODE
cargo build --release --examples --bins *> evidence/bg-port/p0-release.log
Set-Content evidence/bg-port/p0-release.exit $LASTEXITCODE
if ($LASTEXITCODE -eq 0 -and $env:RDI_PROTO_USR -and $env:RDI_PROTO_PWD) {
 & pwsh.exe -NoProfile -File tools/p0-startup.ps1
 cargo test *> evidence/bg-port/p0-test.log
 Set-Content evidence/bg-port/p0-test.exit $LASTEXITCODE
}
