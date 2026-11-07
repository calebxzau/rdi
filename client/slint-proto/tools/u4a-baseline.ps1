$ErrorActionPreference = 'Stop'
$repo = Split-Path (Split-Path $PSScriptRoot)
$evidence = Join-Path $repo 'slint-proto/evidence/ui-port/u4a'
$compose = Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'java.exe' -and $_.CommandLine -match 'calebxzau.rdi.client.MainKt' }
if ($compose) { throw 'Compose launcher is running; close it before compiling shared classes.' }
Set-Location (Join-Path $repo 'slint-proto')
cargo test --workspace --lib --bins *> "$evidence/baseline-rust.log"
$rustCode = $LASTEXITCODE
Set-Location (Join-Path $repo 'ui')
& .\gradlew.bat :client-core:test :ui-logic:test :ui-service:test --continue --no-daemon -x :net:compileTestKotlin *> "$evidence/baseline-kotlin.log"
$coreCode = $LASTEXITCODE
& .\gradlew.bat :test --tests '*LocalModpackInstallViewModelTest' --tests '*ModpackOptionViewModelTest' --no-daemon -x :net:compileTestKotlin *> "$evidence/baseline-viewmodels.log"
$viewCode = $LASTEXITCODE
@{rust=$rustCode;kotlin=$coreCode;viewmodels=$viewCode} | ConvertTo-Json | Set-Content "$evidence/baseline.exit.json"
