$ErrorActionPreference = 'Stop'
$client = Split-Path (Split-Path $PSScriptRoot)
$evidence = Join-Path $client 'slint-proto/evidence/ui-port/u4a'
if (Test-Path "$evidence/kotlin.log") { Copy-Item "$evidence/kotlin.log" "$evidence/kotlin-$([DateTime]::UtcNow.ToString('yyyyMMddHHmmss')).log" }
Set-Location (Join-Path $client 'ui')
& .\gradlew.bat :ui-logic:test :ui-service:test :ui-service:installDist :compileKotlin --no-daemon -x :net:compileTestKotlin *> "$evidence/kotlin.log"
$LASTEXITCODE | Set-Content "$evidence/kotlin.exit"
