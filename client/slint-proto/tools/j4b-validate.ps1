param([string]$Check = 'check')
$ErrorActionPreference = 'Stop'
Set-Location 'C:/Users/calebxzhou/Documents/coding/rdi5/client/slint-proto'
switch ($Check) {
 'check' { cargo check --tests }
 'test' { cargo test --workspace }
 'clippy' { cargo clippy --workspace --all-targets -- -D warnings }
 'release' { cargo build --release }
 'kotlin-fixtures' { Set-Location '../ui'; ./gradlew.bat :client-core:test --tests '*J4bArchiveParityTest' --no-daemon }
 'kotlin' { Set-Location '../ui'; ./gradlew.bat :ui-service:test :client-core:test :ui-service:installDist --no-daemon }
 'install' { Set-Location '../ui'; ./gradlew.bat :ui-service:installDist --no-daemon }
 'mac' { cargo-zigbuild check --release --target aarch64-apple-darwin }
 'linux' { cargo-zigbuild check --release --target x86_64-unknown-linux-gnu }
 default { throw "Unknown check: $Check" }
}
exit $LASTEXITCODE
