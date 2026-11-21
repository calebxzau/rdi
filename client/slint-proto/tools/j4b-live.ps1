param([switch]$Start)
$ErrorActionPreference = 'Stop'
Set-Location 'C:/Users/calebxzhou/Documents/coding/rdi5/client/slint-proto'
$dir = Join-Path $PWD 'evidence/jvm-removal/j4b/live'
New-Item -ItemType Directory -Force "$dir/run" | Out-Null
if ($Start) {
    $env:RDI_DATA_DIR = "$dir/run"
    # Reuse only the designated isolated acceptance account, never the owner's run directory.
    $previous = Join-Path $PWD 'evidence/jvm-removal/j4a/live/run/local_credentials.json'
    if ((Test-Path $previous) -and -not (Test-Path "$dir/run/local_credentials.json")) { Copy-Item $previous "$dir/run/local_credentials.json" }
    cargo preview-run 1> "$dir/preview.log" 2> "$dir/preview.err"
    exit $LASTEXITCODE
}
function Invoke-Mcp([string]$method, $params) {
    $body = @{jsonrpc='2.0'; id=1; method=$method; params=$params} | ConvertTo-Json -Depth 30 -Compress
    $r = Invoke-WebRequest 'http://127.0.0.1:55566/mcp' -Method Post -ContentType 'application/json' -Headers @{Accept='application/json, text/event-stream'} -Body $body -TimeoutSec 20
    $r.Content
}
Invoke-Mcp 'initialize' @{protocolVersion='2024-11-05'; capabilities=@{}; clientInfo=@{name='j4b-validation'; version='1'}}
Invoke-Mcp 'tools/list' @{}
